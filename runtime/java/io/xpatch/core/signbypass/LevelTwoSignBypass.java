package io.xpatch.core.signbypass;

import android.util.Log;
import io.xpatch.core.NativeCalls;
import io.xpatch.core.NativeLoader;
import io.xpatch.hook.XpHook;

/**
 * bypass=2：dobby（inline）版路径重定向。
 *
 * <p>不变量：与 LevelOne 同样的四条前置检查；但 {@code enable_io_redirect_dobby} 在 .so 里导出为
 * void（见 NativeCalls 的说明），拿不到 hooked 计数，所以"调用未抛异常"只能算**已提交**，
 * 日志必须写明这一点，不能宣称"已确认 hook 成功"。
 */
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
