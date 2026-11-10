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
    public final boolean fromConfig;

    private SigningDetailsData(int scheme, Signature[] apk, Signature[] history, boolean fromConfig) {
        this.scheme = scheme;
        this.apk = apk == null ? new Signature[0] : apk;
        this.history = history == null ? this.apk : history;
        this.fromConfig = fromConfig;
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
            JSONObject object = new JSONObject(text);
            int scheme = object.optInt("scheme", SCHEME_UNKNOWN);
            // signing_details 是可信基线：任何一张证书解析失败都视为整份不可信（返回 null），
            // 由调用方退回 legacy sign；绝不能静默缩表，否则按位置配对会映射成另一张证书。
            Signature[] apk = parseSignatures(object.optJSONArray("apk"), "apk");
            if (apk == null) {
                return null;
            }
            if (apk.length == 0) {
                return null;
            }
            Signature[] history = parseSignatures(object.optJSONArray("history"), "history");
            if (history == null) {
                return null;
            }
            if (history.length == 0) {
                history = apk;
            }
            return new SigningDetailsData(scheme, apk, history, true);
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "解析 signing_details 失败，退回 legacy sign：" + th);
            return null;
        }
    }

    public static SigningDetailsData fromSignatures(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        return new SigningDetailsData(SCHEME_UNKNOWN, cloneSignatures(signatures), cloneSignatures(signatures), false);
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
            copy[i] = signature == null ? null : new Signature(signature.toByteArray());
        }
        return copy;
    }

    /**
     * 返回 null 表示"这份 signing_details 不可信"（调用方必须 fail-closed）。
     * 空数组表示字段缺省（合法）。空串、null、非 hex 都算失败。
     */
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
