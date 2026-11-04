package io.xpatch.core.signbypass;

import io.xpatch.core.NativeCalls;
import io.xpatch.hook.AppEnv;

class LevelTwoSignBypass extends BaseNativeSignBypass {
    LevelTwoSignBypass() {
    }

    @Override
    public void enable() throws Exception {
        NativeCalls.enable_io_redirect_dobby(AppEnv.selfApk, getRedirectTarget());
        PMSignBypass.enableSignBypassByHook();
    }
}
