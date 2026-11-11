package io.xpatch.core.signbypass;

import android.util.Log;
import io.xpatch.core.NativeCalls;
import io.xpatch.core.NativeLoader;
import io.xpatch.hook.XpHook;

/**
 * bypass=1：libc 层精确路径重定向（open/openat/fopen，UI 上叫 Seccomp 档）。
 *
 * <p>不变量：只有"本包路径 + 原包真实存在 + hook_core.so 已加载 + native 返回 true"四条同时成立才算启用；
 * 任何一条不成立都返回 false，并写明降级原因，禁止在日志里写"已启用"（审查 P1-2 / P2 假成功）。
 */
class LevelOneSignBypass extends BaseNativeSignBypass {

    private static final String TAG = "XpLevelOne";

    LevelOneSignBypass() {
    }

    @Override
    public boolean enable() throws Exception {
        String self = SignBypass.selfPath();
        if (self == null) {
            XpHook.log0(TAG, "native io 重定向未启用：本包 APK 路径未知（AppEnv.selfApk 为空）");
            return false;
        }
        String redirectTarget = getRedirectTarget();
        if (redirectTarget == null || redirectTarget.length() == 0) {
            XpHook.log0(TAG, "native io 重定向未启用：没有字节级原包（打包时未内嵌原包）");
            return false;
        }
        String target = SignBypass.targetPath(redirectTarget);
        if (target == null) {
            XpHook.log0(TAG, "native io 重定向未启用：原包文件不存在或无法规范化：" + redirectTarget);
            return false;
        }
        if (!NativeCalls.loaded()) {
            XpHook.log0(TAG, "native io 重定向未启用：hook_core.so 未加载（"
                    + NativeLoader.lastError() + "）");
            return false;
        }
        try {
            if (!NativeCalls.enable_io_redirect(self, target)) {
                XpHook.log0(TAG, "native io 重定向未启用：enable_io_redirect 返回 false"
                        + "（open/openat/fopen 一个都没 hook 上）");
                return false;
            }
            XpHook.log0(TAG, "native io 重定向已启用：" + self + " -> " + target);
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "native io 重定向不可用（已降级）：" + Log.getStackTraceString(th));
            return false;
        }
    }
}
