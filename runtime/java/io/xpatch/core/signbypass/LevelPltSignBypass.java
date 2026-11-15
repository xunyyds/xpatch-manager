package io.xpatch.core.signbypass;

import android.util.Log;
import io.xpatch.core.NativeCalls;
import io.xpatch.core.NativeLoader;
import io.xpatch.hook.XpHook;

class LevelPltSignBypass extends BaseNativeSignBypass {

    private static final String TAG = "XpPlt";

    LevelPltSignBypass() {
    }

    @Override
    public boolean enable() throws Exception {
        String self = SignBypass.selfPath();
        if (self == null) {
            XpHook.log0(TAG, "PLT 重定向未启用：本包 APK 路径未知（AppEnv.selfApk 为空）");
            return false;
        }
        String redirectTarget = getRedirectTarget();
        if (redirectTarget == null || redirectTarget.length() == 0) {
            XpHook.log0(TAG, "PLT 重定向未启用：没有字节级原包（打包时未内嵌原包）");
            return false;
        }
        String target = SignBypass.targetPath(redirectTarget);
        if (target == null) {
            XpHook.log0(TAG, "PLT 重定向未启用：原包文件不存在或无法规范化：" + redirectTarget);
            return false;
        }
        if (!NativeCalls.loaded()) {
            XpHook.log0(TAG, "PLT 重定向不可用：hook_core.so 未加载（"
                    + NativeLoader.lastError() + "）。该版本管理器不支持 PLT 档，请改用 File / Seccomp / Extreme");
            Log.e(TAG, "PLT 档不可用：hook_core.so 未加载，NativeCalls 恒不可用");
            return false;
        }
        try {
            if (!NativeCalls.enable_io_redirect_plt(self, target)) {
                XpHook.log0(TAG, "PLT 重定向未生效：GOT 槽一个都没改写成功"
                        + "（宿主可能用 -fno-plt 编译，或库不在 /data 下）。"
                        + "本档位标记为实验性，已降级为 Java 层过签");
                Log.e(TAG, "PLT 档 patched=0：详见 _framework 日志的「plt redirect initial」行");
                return false;
            }
            XpHook.log0(TAG, "PLT 重定向已启用：" + self + " -> " + target);
            return true;
        } catch (UnsatisfiedLinkError error) {
            XpHook.log0(TAG, "PLT 重定向不可用：该版本管理器没有 enable_io_redirect_plt 符号"
                    + "（发布包的 hook_core.so 过旧），请更新管理器");
            Log.e(TAG, "PLT 档不可用：缺少 enable_io_redirect_plt 导出");
            return false;
        } catch (Throwable th) {
            XpHook.log0(TAG, "PLT 重定向不可用（已降级）：" + Log.getStackTraceString(th));
            Log.e(TAG, "PLT 档异常：" + th);
            return false;
        }
    }
}
