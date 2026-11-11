package io.xpatch.core.signbypass;

import android.util.Log;
import io.xpatch.core.NativeCalls;
import io.xpatch.core.NativeLoader;
import io.xpatch.hook.XpHook;

class LevelTwoSignBypass extends BaseNativeSignBypass {

    private static final String TAG = "XpLevelTwo";

    LevelTwoSignBypass() {
    }

    @Override
    public boolean enable() throws Exception {
        String self = SignBypass.selfPath();
        if (self == null) {
            XpHook.log0(TAG, "dobby 重定向未启用：本包 APK 路径未知（AppEnv.selfApk 为空）");
            return false;
        }
        String redirectTarget = getRedirectTarget();
        if (redirectTarget == null || redirectTarget.length() == 0) {
            XpHook.log0(TAG, "dobby 重定向未启用：没有字节级原包（打包时未内嵌原包）");
            return false;
        }
        String target = SignBypass.targetPath(redirectTarget);
        if (target == null) {
            XpHook.log0(TAG, "dobby 重定向未启用：原包文件不存在或无法规范化：" + redirectTarget);
            return false;
        }
        if (!NativeCalls.loaded()) {
            XpHook.log0(TAG, "dobby 重定向未启用：hook_core.so 未加载（" + NativeLoader.lastError() + "）");
            return false;
        }
        try {
            NativeCalls.enable_io_redirect_dobby(self, target);
            XpHook.log0(TAG, "dobby 重定向已提交：" + self + " -> " + target
                    + "（该 JNI 返回 void，无法回报 hooked 计数）");
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "dobby 重定向不可用（已降级）：" + Log.getStackTraceString(th));
            return false;
        }
    }
}
