package io.xpatch.core.signbypass;

import io.xpatch.core.NativeCalls;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;

class MixSignBypass extends BaseNativeSignBypass {
    MixSignBypass() {
    }

    @Override
    public void enable() throws Exception {
        PMSignBypass.enableSignBypassByHook();
        String redirectTarget = getRedirectTarget();
        NativeCalls.enable_io_redirect_mix(AppEnv.selfApk, redirectTarget);
        XpHook.log0("XPatch", "混合 native 重定向已启用：从 " + AppEnv.selfApk + " to " + redirectTarget);
    }
}
