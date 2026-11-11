package io.xpatch.core;

import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

public final class XpLayout {

    private static final String TAG = "XpLayout";

    private static final int COL_LOGICAL = 0;
    private static final int COL_LEGACY = 1;
    private static final int COL_KEY = 2;

    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    private static final int MAX_CONFIG_PROBES = 2048;

    private static final java.util.HashSet<String> CONFIG_EXTS = new java.util.HashSet<String>(
            java.util.Arrays.asList("json", "dat", "bin", "txt", "lst"));

    private static final String LEGACY_CONFIG = "xp/config.json";

    private static final String HOOK_SO_ABI_PREFIX = "hook_so_";

    private static final String HOOK_SO_ABI_KEY_PREFIX = "res_hook_so_";

    private static final String[][] TABLE = {
            {"core", "xp/core.dex", "res_core"},
            {"hook_dex", "xp/hook_core.dex", "res_hook_dex"},
            {"hook_so", "xp/hook_core.so", "res_hook_so"},
            {"source", "xp/source.apk", "res_source"},
            {"source_manifest", "xp/source.manifest", "res_source_manifest"},
            {"pkg", "xp/pkg", "res_pkg"},
            {"file_provider", "xp/file_provider", "res_file_provider"},
            {"dex89", "xp/xp89.dex", "res_dex89"},
            {"dex100", "xp/xp100.dex", "res_dex100"},
            {"dex101", "xp/xp101.dex", "res_dex101"},
            {"dex102", "xp/xp102.dex", "res_dex102"},
            {"config", LEGACY_CONFIG, "res_cfg"},
            {"o_source", "xp/o_app.apk", "res_o_source"},
    };


    private static final Object LOCK = new Object();

    private static volatile Layout layout;

    private static volatile int attempts;

    private static final int MAX_ATTEMPTS = 2;

    private XpLayout() {
    }

    public static String get(String logical) {
        try {
            ensureInstalled();
            Layout current = layout;
            if (current != null && logical != null) {
                String path = current.paths.get(logical);
                if (path != null) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
        }
        return legacy(logical);
    }

    public static String configEntry() {
        try {
            ensureInstalled();
            Layout current = layout;
            if (current != null && current.config != null) {
                return current.config;
            }
        } catch (Throwable ignored) {
        }
        return LEGACY_CONFIG;
    }

    public static void ensureInstalled() {
        Layout current = layout;
        if (current != null || attempts >= MAX_ATTEMPTS) {
            return;
        }
        if (selfApk() == null) {
            return;
        }
        synchronized (LOCK) {
            if (layout != null || attempts >= MAX_ATTEMPTS || selfApk() == null) {
                return;
            }
            attempts++;
            Layout discovered = null;
            try {
                discovered = discover();
            } catch (Throwable ignored) {
            }
            layout = discovered;
        }
    }

    public static String readConfigText(ZipFile zip) {
        if (zip == null) {
            return null;
        }
        try {
            String name = discoverConfigEntry(zip);
            if (name == null) {
                return null;
            }
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_CONFIG_BYTES) {
                return null;
            }
            byte[] raw = readEntry(zip, entry, MAX_CONFIG_BYTES);
            return raw == null ? null : decode(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Layout discover() {
        String apk = selfApk();
        if (apk == null) {
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            String configEntry = discoverConfigEntry(zip);
            if (configEntry == null) {
                return new Layout(null, null);
            }
            ZipEntry entry = zip.getEntry(configEntry);
            byte[] raw = entry == null ? null : readEntry(zip, entry, MAX_CONFIG_BYTES);
            return parse(configEntry, raw);
        } catch (Throwable th) {
            return null;
        } finally {
            closeQuietly(zip);
        }
    }

    private static String discoverConfigEntry(ZipFile zip) {
        List<ZipEntry> items = new ArrayList<ZipEntry>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry != null && !entry.isDirectory()) {
                items.add(entry);
            }
        }
        int budget = MAX_CONFIG_PROBES;
        for (int index = items.size() - 1; index >= 0; index--) {
            ZipEntry entry = items.get(index);
            if (!mayBeConfig(entry.getName(), entry.getSize())) {
                continue;
            }
            if (budget-- <= 0) {
                break;
            }
            if (isLayoutConfig(readEntry(zip, entry, MAX_CONFIG_BYTES))) {
                return entry.getName();
            }
        }
        if (zip.getEntry(LEGACY_CONFIG) != null) {
            return LEGACY_CONFIG;
        }
        return null;
    }

    private static boolean mayBeConfig(String name, long size) {
        if (name == null || name.length() == 0 || size <= 0 || size > MAX_CONFIG_BYTES) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot <= slash || dot == name.length() - 1) {
            return true;
        }
        return CONFIG_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.US));
    }

    private static boolean isLayoutConfig(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_CONFIG_BYTES) {
            return false;
        }
        String text = decode(raw);
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() == 0 || trimmed.charAt(0) != '{') {
            return false;
        }
        return text.indexOf("\"res_cfg\"") >= 0;
    }

    private static Layout parse(String configEntry, byte[] raw) {
        String text = raw == null ? null : decode(raw);
        if (text == null) {
            return new Layout(configEntry, null);
        }
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Throwable th) {
            return new Layout(configEntry, null);
        }
        Map<String, String> paths = new HashMap<String, String>();
        try {
            for (int i = 0; i < TABLE.length; i++) {
                String path = value(json, TABLE[i][COL_KEY]);
                if (path != null) {
                    paths.put(TABLE[i][COL_LOGICAL], path);
                }
            }
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key == null || !key.startsWith(HOOK_SO_ABI_KEY_PREFIX)
                        || key.length() <= HOOK_SO_ABI_KEY_PREFIX.length()) {
                    continue;
                }
                String path = value(json, key);
                if (path != null) {
                    paths.put(HOOK_SO_ABI_PREFIX + key.substring(HOOK_SO_ABI_KEY_PREFIX.length()), path);
                }
            }
        } catch (Throwable ignored) {
        }
        if (!paths.isEmpty()) {
            logQuietly("注入布局：config=" + configEntry + "，v=" + optInt(json, "res_v")
                    + "，enc=" + optInt(json, "res_enc")
                    + "，dir=" + orDash(value(json, "res_dir"))
                    + "，boot=" + orDash(value(json, "res_boot_dex"))
                    + "，factory=" + orDash(value(json, "res_factory"))
                    + "，runtime=" + orDash(value(json, "res_runtime_class"))
                    + "，provider=" + orDash(value(json, "res_provider"))
                    + "，res=" + paths.size() + " 项");
        }
        return new Layout(configEntry, paths);
    }

    private static String value(JSONObject json, String key) {
        try {
            String value = json.optString(key, "");
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.length() == 0 || "null".equals(trimmed)) {
                return null;
            }
            return trimmed;
        } catch (Throwable th) {
            return null;
        }
    }

    private static int optInt(JSONObject json, String key) {
        try {
            return json.optInt(key, 0);
        } catch (Throwable th) {
            return 0;
        }
    }

    private static String legacy(String logical) {
        if (logical == null) {
            return "";
        }
        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i][COL_LOGICAL].equals(logical)) {
                return TABLE[i][COL_LEGACY];
            }
        }
        if (logical.startsWith(HOOK_SO_ABI_PREFIX)) {
            return "xp/hook_core_" + logical.substring(HOOK_SO_ABI_PREFIX.length()) + ".so";
        }
        return "xp/" + logical;
    }

    private static String selfApk() {
        String apk = AppEnv.selfApk;
        if (apk == null || apk.trim().length() == 0) {
            apk = AppEnv.wrapperApk;
        }
        if (apk == null || apk.trim().length() == 0) {
            return null;
        }
        return apk;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) {
        InputStream in = null;
        try {
            in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) > 0) {
                total += count;
                if (total > limit) {
                    return null;
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Throwable th) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String decode(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new String(raw, "UTF-8");
        } catch (Throwable th) {
            return null;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    private static void logQuietly(String message) {
        try {
            XpHook.log0(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    private static final class Layout {

        final String config;

        final Map<String, String> paths;

        Layout(String config, Map<String, String> paths) {
            this.config = config;
            this.paths = paths == null ? Collections.<String, String>emptyMap() : paths;
        }
    }
}



