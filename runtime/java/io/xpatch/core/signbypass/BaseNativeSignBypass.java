package io.xpatch.core.signbypass;

import android.text.TextUtils;
import io.xpatch.hook.AppEnv;

abstract class BaseNativeSignBypass implements SignBypassLevel {
    BaseNativeSignBypass() {
    }

    protected String getRedirectTarget() {
        if (TextUtils.isEmpty(AppEnv.o_apk_path)) {
            return AppEnv.source_apk_path;
        }
        return AppEnv.o_apk_path;
    }
}
