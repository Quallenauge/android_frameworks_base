/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.hardware.camera2.impl;

import android.app.ActivityThread;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Lets a camera app written for another vendor's camera HAL run on this one.
 *
 * Such an app reads and writes vendor tags of its own HAL without expecting
 * them to be absent; the lookup of an unknown tag throws
 * {@link IllegalArgumentException}. For a package that has a file
 * {@code /system_ext/etc/camera_vendor_tag_compat/<package>.conf}, a read of
 * an unknown tag returns the value given in that file, or {@code null}. A
 * write of an unknown tag is kept in the metadata object, so that the app
 * reads back what it wrote; it is not sent to the camera service.
 *
 * File format:
 * <pre>
 * # comment
 * [*]                      values for every camera and for requests/results
 * some.vendor.tag = 1
 * [0]                      values for camera 0 only, searched first
 * some.vendor.name = "text"
 * some.vendor.sizes = 4624, 3468, 1920, 1080
 * </pre>
 * A value is converted to the type of the key that asks for it: numbers,
 * {@code true}/{@code false}, comma separated arrays, and quoted text for
 * {@code byte[]} and {@code String}.
 *
 * @hide
 */
public final class VendorTagCompat {
    private static final String TAG = "VendorTagCompat";
    private static final String CONFIG_DIR = "/system_ext/etc/camera_vendor_tag_compat/";
    private static final String ALL_CAMERAS = "*";

    private static final Object sLock = new Object();
    private static boolean sLoaded;
    /** Read without the lock on the hot path; only ever goes from false to true. */
    private static volatile boolean sActive;
    /** Section name to (tag name to raw value). */
    private static final ArrayMap<String, ArrayMap<String, String>> sValues = new ArrayMap<>();
    private static final ArraySet<String> sMissing = new ArraySet<>();
    private static final ArraySet<String> sLogged = new ArraySet<>();

    private VendorTagCompat() {
    }

    /** Whether this process runs a package with a compat file. */
    public static boolean isActive() {
        if (sLoaded) {
            return sActive;
        }
        synchronized (sLock) {
            if (!sLoaded) {
                load();
                sLoaded = true;
            }
            return sActive;
        }
    }

    /** Whether the tag is already known not to exist, so the lookup can be skipped. */
    public static boolean isKnownMissing(String name) {
        if (!sActive) {
            return false;
        }
        synchronized (sLock) {
            return sMissing.contains(name);
        }
    }

    /**
     * Value for a tag that the HAL does not define.
     *
     * @return the configured value in the type of the key, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(int cameraId, String name, Class<T> type) {
        String raw;
        synchronized (sLock) {
            sMissing.add(name);
            raw = lookup(Integer.toString(cameraId), name);
            if (raw == null) {
                raw = lookup(ALL_CAMERAS, name);
            }
        }
        Object value = null;
        if (raw != null) {
            try {
                value = convert(raw, type);
            } catch (RuntimeException e) {
                Log.e(TAG, "Bad value for " + name + " (" + type.getSimpleName() + "): " + raw);
            }
        }
        logOnce("get " + name + " (" + type.getSimpleName() + ") camera " + cameraId
                + " -> " + (value == null ? "null" : raw));
        return (T) value;
    }

    /** Notes a write to a tag that the HAL does not define. */
    public static void noteSet(String name) {
        synchronized (sLock) {
            sMissing.add(name);
        }
        logOnce("set " + name + " (kept in the app, not sent to the HAL)");
    }

    /** Whether a stored value can be returned for a key of this type. */
    public static boolean isInstance(Class<?> type, Object value) {
        return box(type).isInstance(value);
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        } else if (type == long.class) {
            return Long.class;
        } else if (type == byte.class) {
            return Byte.class;
        } else if (type == float.class) {
            return Float.class;
        } else if (type == double.class) {
            return Double.class;
        } else if (type == boolean.class) {
            return Boolean.class;
        } else if (type == short.class) {
            return Short.class;
        }
        return type;
    }

    private static String lookup(String section, String name) {
        ArrayMap<String, String> values = sValues.get(section);
        return values != null ? values.get(name) : null;
    }

    private static void logOnce(String message) {
        synchronized (sLock) {
            if (!sLogged.add(message)) {
                return;
            }
        }
        Log.i(TAG, message);
    }

    private static void load() {
        String packageName = ActivityThread.currentPackageName();
        if (packageName == null) {
            return;
        }
        File file = new File(CONFIG_DIR + packageName + ".conf");
        if (!file.isFile()) {
            return;
        }
        String section = ALL_CAMERAS;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                ArrayMap<String, String> values = sValues.get(section);
                if (values == null) {
                    values = new ArrayMap<>();
                    sValues.put(section, values);
                }
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            Log.e(TAG, "Cannot read " + file, e);
        }
        Log.i(TAG, "Active for " + packageName);
        sActive = true;
    }

    private static boolean isQuoted(String raw) {
        return raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"");
    }

    private static String[] split(String raw) {
        if (raw.isEmpty()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static Object convert(String raw, Class<?> type) {
        if (type == String.class) {
            return isQuoted(raw) ? raw.substring(1, raw.length() - 1) : raw;
        }
        if (type == byte[].class) {
            if (isQuoted(raw)) {
                return raw.substring(1, raw.length() - 1).getBytes(StandardCharsets.UTF_8);
            }
            String[] parts = split(raw);
            byte[] out = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = (byte) Integer.decode(parts[i]).intValue();
            }
            return out;
        }
        if (type == int[].class) {
            String[] parts = split(raw);
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.decode(parts[i]);
            }
            return out;
        }
        if (type == long[].class) {
            String[] parts = split(raw);
            long[] out = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Long.decode(parts[i]);
            }
            return out;
        }
        if (type == float[].class) {
            String[] parts = split(raw);
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i]);
            }
            return out;
        }
        if (type == double[].class) {
            String[] parts = split(raw);
            double[] out = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i]);
            }
            return out;
        }
        if (type == Integer[].class) {
            String[] parts = split(raw);
            Integer[] out = new Integer[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.decode(parts[i]);
            }
            return out;
        }
        if (type == Float[].class) {
            String[] parts = split(raw);
            Float[] out = new Float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i]);
            }
            return out;
        }
        if (type == Integer.class || type == int.class) {
            return Integer.decode(raw);
        }
        if (type == Long.class || type == long.class) {
            return Long.decode(raw);
        }
        if (type == Byte.class || type == byte.class) {
            return (byte) Integer.decode(raw).intValue();
        }
        if (type == Float.class || type == float.class) {
            return Float.parseFloat(raw);
        }
        if (type == Double.class || type == double.class) {
            return Double.parseDouble(raw);
        }
        if (type == Boolean.class || type == boolean.class) {
            return raw.equals("1") || raw.equalsIgnoreCase("true");
        }
        return null;
    }
}
