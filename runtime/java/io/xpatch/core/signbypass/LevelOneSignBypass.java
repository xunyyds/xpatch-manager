package io.xpatch.core.signbypass;

import io.xpatch.core.NativeCalls;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;

class LevelOneSignBypass extends BaseNativeSignBypass {
    LevelOneSignBypass() {
    }

    @Override
    public void enable() throws Exception {
        PMSignBypass.enableSignBypassByHook();
        XpHook.log0("XPatch", "Java 签名伪装已安装，bypass_type=" + AppEnv.sign_bypass_type);

        String redirectTarget = getRedirectTarget();
        if (redirectTarget == null || redirectTarget.length() == 0) {
            XpHook.log0("XPatch", "跳过 io 重定向：没有原包（未开启 embedSource）");
            return;
        }
        try {
            NativeCalls.enable_io_redirect(AppEnv.selfApk, redirectTarget);
            XpHook.log0("XPatch", "io 重定向已启用：从 " + AppEnv.selfApk + " 到 " + redirectTarget);
        } catch (Throwable th) {
            XpHook.log0("XPatch", "io 重定向不可用（已降级）：" + th);
        }
    }
}
