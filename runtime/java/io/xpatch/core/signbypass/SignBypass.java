package io.xpatch.core.signbypass;

import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;

public class SignBypass {

    private static final String TAG = "XpSignBypass";

    public static int normalizeType(int i) {
        if (i == 0 || i == 1 || i == 2 || i == 3 || i == 4) {
            return i;
        }
        return 1;
    }

    public static boolean enable() throws Exception {
        SignBypassLevel level;
        int type = AppEnv.sign_bypass_type;
        if (type == 1) {
            level = new LevelOneSignBypass();
        } else if (type == 2) {
            level = new LevelTwoSignBypass();
        } else if (type == 3) {
            level = new MixSignBypass();
        } else if (type == 4) {
            level = new LevelPltSignBypass();
        } else {
            XpHook.log0(TAG, "没有 native 层可启用（bypass=" + type + "），仅保留 Java 层");
            return false;
        }
        boolean enabled = level.enable();
        XpHook.log0(TAG, "native 层级结果：bypass=" + type + "，已启用=" + enabled);
        return enabled;
    }

    static String selfPath() {
        return canonicalPath(AppEnv.selfApk);
    }

    static String targetPath(String raw) {
        String canonical = canonicalPath(raw);
        if (canonical == null) {
            return null;
        }
        return new File(canonical).isFile() ? canonical : null;
    }

    static String canonicalPath(String path) {
        if (path == null || path.trim().length() == 0) {
            return null;
        }
        File file = new File(path.trim());
        try {
            return file.getCanonicalPath();
        } catch (Throwable th) {
            try {
                return file.getAbsolutePath();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
