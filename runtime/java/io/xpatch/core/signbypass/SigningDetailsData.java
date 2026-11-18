package io.xpatch.core.signbypass;

import android.content.pm.Signature;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SigningDetailsData {

    public static final int SCHEME_UNKNOWN = 0;

    private static volatile SigningDetailsData current;

    public final int scheme;
    public final Signature[] apk;
    public final Signature[] history;
    public final Signature[] oldest;
    public final boolean fromConfig;

    private SigningDetailsData(int scheme, Signature[] apk, Signature[] history, Signature[] oldest,
                               boolean fromConfig) {
        this.scheme = scheme;
        this.apk = apk == null ? new Signature[0] : apk;
        this.history = history == null || history.length == 0 ? this.apk : history;
        this.oldest = oldest == null || oldest.length == 0
                ? pickOldest(this.apk, this.history) : oldest;
        this.fromConfig = fromConfig;
    }

    private static Signature[] pickOldest(Signature[] apk, Signature[] history) {
        if (history == null || history.length == 0) {
            return apk;
        }
        if (apk == null || apk.length == 0) {
            return history;
        }
        if (history.length < 2) {
            return apk;
        }
        byte[] first = history[0] == null ? null : history[0].toByteArray();
        boolean historyStartsWithCurrent = first != null && apk[0] != null
                && java.util.Arrays.equals(first, apk[0].toByteArray());
        return historyStartsWithCurrent ? new Signature[]{history[history.length - 1]}
                : new Signature[]{history[0]};
    }

    public static SigningDetailsData fromJson(String json) {
        if (json == null) {
            return null;
        }
        String text = json.trim();
        if (text.length() == 0 || "null".equals(text) || "{}".equals(text)) {
            return null;
        }
        try {
            JSONObject object = unwrapObject(text);
            if (object == null) {
                return null;
            }
            int scheme = object.optInt("scheme", SCHEME_UNKNOWN);
            Signature[] apk = parseSignatures(parseArrayField(object, "apk"), "apk");
            if (apk == null) {
                return null;
            }
            if (apk.length == 0) {
                return null;
            }
            Signature[] history = parseSignatures(parseArrayField(object, "history"), "history");
            if (history == null) {
                return null;
            }
            if (history.length == 0) {
                history = apk;
            }
            Signature[] oldest = parseSignatures(parseArrayField(object, "oldest"), "oldest");
            if (oldest == null) {
                oldest = pickOldest(apk, history);
            }
            return new SigningDetailsData(scheme, apk, history, oldest, true);
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "解析 signing_details 失败，退回 legacy sign：" + th);
            return null;
        }
    }

    private static JSONObject unwrapObject(String text) {
        Object parsed = parseMaybeEncoded(text);
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        if (parsed instanceof String) {
            String inner = ((String) parsed).trim();
            if (inner.length() > 0 && !inner.equals(text)) {
                Object second = parseMaybeEncoded(inner);
                if (second instanceof JSONObject) {
                    return (JSONObject) second;
                }
            }
        }
        return null;
    }

    private static Object parseMaybeEncoded(String text) {
        try {
            return new org.json.JSONTokener(text).nextValue();
        } catch (Throwable ignored) {
        }
        try {
            return new JSONArray(text);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONArray parseArrayField(JSONObject object, String field) {
        Object value = object.opt(field);
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.length() == 0 || "null".equals(text)) {
                return new JSONArray();
            }
            Object parsed = parseMaybeEncoded(text);
            if (parsed instanceof JSONArray) {
                return (JSONArray) parsed;
            }
            if (parsed instanceof String) {
                Object second = parseMaybeEncoded(((String) parsed).trim());
                if (second instanceof JSONArray) {
                    return (JSONArray) second;
                }
            }
            return new JSONArray();
        }
        return object.optJSONArray(field);
    }

    public static SigningDetailsData fromSignatures(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        return new SigningDetailsData(SCHEME_UNKNOWN, cloneSignatures(signatures),
                cloneSignatures(signatures), cloneSignatures(signatures), false);
    }

    public static synchronized void init(String json, Signature[] legacySignatures) {
        SigningDetailsData parsed = fromJson(json);
        if (parsed == null) {
            parsed = fromSignatures(legacySignatures);
        }
        current = parsed;
    }

    public static SigningDetailsData get() {
        return current;
    }

    public boolean hasApk() {
        return apk != null && apk.length > 0;
    }

    public boolean hasHistory() {
        return history != null && history.length > 0;
    }

    public boolean hasRotation() {
        if (history == null || history.length < 2) {
            return false;
        }
        byte[] first = history[0] == null ? null : history[0].toByteArray();
        byte[] last = history[history.length - 1] == null ? null : history[history.length - 1].toByteArray();
        return first != null && last != null && !java.util.Arrays.equals(first, last);
    }

    public String describe() {
        return "scheme=" + (scheme == SCHEME_UNKNOWN ? "未知" : String.valueOf(scheme))
                + "，来源=" + (fromConfig ? "signing_details" : "legacy sign")
                + "，证书数=" + apk.length
                + "，历史数=" + history.length
                + "，首证指纹=" + sha256Prefix(apk.length > 0 && apk[0] != null ? apk[0].toByteArray() : null);
    }

    public static String sha256Prefix(byte[] data) {
        if (data == null || data.length == 0) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                int value = digest[i] & 0xFF;
                if (value < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(value));
            }
            return sb.toString();
        } catch (Throwable th) {
            return "unknown";
        }
    }

    public static Signature[] cloneSignatures(Signature[] signatures) {
        if (signatures == null) {
            return new Signature[0];
        }
        Signature[] copy = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++) {
            Signature signature = signatures[i];
            copy[i] = signature == null ? null : withFlags(signature, flagsOf(signature));
        }
        return copy;
    }

    public static Signature withFlags(Signature source, int flags) {
        if (source == null) {
            return null;
        }
        Signature derived = new Signature(source.toByteArray());
        setFlags(derived, flags);
        return derived;
    }

    private static void setFlags(Signature target, int flags) {
        try {
            java.lang.reflect.Method method = Signature.class.getMethod("setFlags", Integer.TYPE);
            method.invoke(target, Integer.valueOf(flags));
        } catch (Throwable ignored) {
        }
    }

    public static int flagsOf(Signature signature) {
        if (signature == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method method = Signature.class.getMethod("getFlags", new Class[0]);
            Object value = method.invoke(signature, new Object[0]);
            if (value instanceof Integer) {
                return ((Integer) value).intValue();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Signature[] parseSignatures(JSONArray array, String field) {
        if (array == null) {
            return new Signature[0];
        }
        List<Signature> list = new ArrayList<Signature>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String hex = array.optString(i, null);
            if (hex == null || hex.trim().length() == 0) {
                XpHook.log0("XpSignBypass", "signing_details." + field + " 第 " + i + " 项为空，整份不可信，退回 legacy sign");
                return null;
            }
            try {
                byte[] raw = DataUtils.hexToByteArray(hex);
                if (raw == null || raw.length == 0) {
                    XpHook.log0("XpSignBypass", "signing_details." + field + " 第 " + i + " 项解出空字节，整份不可信");
                    return null;
                }
                list.add(new Signature(raw));
            } catch (Throwable th) {
                XpHook.log0("XpSignBypass", "signing_details." + field + " 第 " + i + " 张证书解析失败，整份不可信：" + th);
                return null;
            }
        }
        return list.toArray(new Signature[list.size()]);
    }
}
