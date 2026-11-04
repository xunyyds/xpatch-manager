package io.xpatch.core.signbypass;

import io.xpatch.core.NativeCalls;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;

class LevelOneSignBypass extends BaseNativeSignBypass {
    LevelOneSignBypass() {
    }

    @Override
    public void enable() throws Exception {
        String redirectTarget = getRedirectTarget();
        NativeCalls.enable_io_redirect(AppEnv.selfApk, redirectTarget);
        PMSignBypass.enableSignBypassByHook();
        XpHook.log0("XPatch", "io redirect enabled from " + AppEnv.selfApk + " to " + redirectTarget);
    }
}
