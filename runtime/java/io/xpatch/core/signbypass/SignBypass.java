package io.xpatch.core.signbypass;

import io.xpatch.hook.AppEnv;

public class SignBypass {
    public static int normalizeType(int i) {
        if (i == 0 || i == 1 || i == 2 || i == 3) {
            return i;
        }
        return 1;
    }

    public static void enable() throws Exception {
        SignBypassLevel levelOneSignBypass;
        int i = AppEnv.sign_bypass_type;
        if (i == 1) {
            levelOneSignBypass = new LevelOneSignBypass();
        } else if (i == 2) {
            levelOneSignBypass = new LevelTwoSignBypass();
        } else {
            levelOneSignBypass = i != 3 ? null : new MixSignBypass();
        }
        if (levelOneSignBypass != null) {
            levelOneSignBypass.enable();
        }
    }
}
