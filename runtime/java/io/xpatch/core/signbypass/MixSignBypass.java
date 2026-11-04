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
        XpHook.log0("XPatch", "mix native redirect enabled from " + AppEnv.selfApk + " to " + redirectTarget);
    }
}
