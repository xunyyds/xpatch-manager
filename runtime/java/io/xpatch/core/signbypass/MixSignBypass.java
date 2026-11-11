package io.xpatch.core.signbypass;

import android.util.Log;
import io.xpatch.core.NativeCalls;
import io.xpatch.core.NativeLoader;
import io.xpatch.hook.XpHook;

/**
 * bypass=3：dobby + inline 混合重定向。
 *
 * <p>不变量：与 LevelOne 相同的四条前置检查（原实现既不判空也不 catch，native 缺失时异常会冒泡到
 * installSignBypass 外层，且返回值被丢弃）；{@code enable_io_redirect_mix} 返回 boolean，必须检查。
 */
class MixSignBypass extends BaseNativeSignBypass {

    private static final String TAG = "XpMix";

    MixSignBypass() {
    }

    @Override
    public boolean enable() throws Exception {
        String self = SignBypass.selfPath();
        if (self == null) {
            XpHook.log0(TAG, "混合 native 重定向未启用：本包 APK 路径未知（AppEnv.selfApk 为空）");
            return false;
        }
        String redirectTarget = getRedirectTarget();
        if (redirectTarget == null || redirectTarget.length() == 0) {
            XpHook.log0(TAG, "混合 native 重定向未启用：没有字节级原包（打包时未内嵌原包）");
            return false;
        }
        String target = SignBypass.targetPath(redirectTarget);
        if (target == null) {
            XpHook.log0(TAG, "混合 native 重定向未启用：原包文件不存在或无法规范化：" + redirectTarget);
            return false;
        }
        if (!NativeCalls.loaded()) {
            XpHook.log0(TAG, "混合 native 重定向未启用：hook_core.so 未加载（"
                    + NativeLoader.lastError() + "）");
            return false;
        }
        try {
            if (!NativeCalls.enable_io_redirect_mix(self, target)) {
                XpHook.log0(TAG, "混合 native 重定向未启用：enable_io_redirect_mix 返回 false"
                        + "（两种 hook 方式都没挂上）");
                return false;
            }
            XpHook.log0(TAG, "混合 native 重定向已启用：" + self + " -> " + target);
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "混合 native 重定向不可用（已降级）：" + Log.getStackTraceString(th));
            return false;
        }
    }
}
