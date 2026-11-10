package io.xpatch.core.signbypass;

import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;

/**
 * native io 重定向的层级分派器。
 *
 * <p>不变量：
 * <ol>
 *   <li>bypass_sign_type 1/2/3 三种实现都要可达：旧实现被 {@code XpInit} 的"== 1"门禁卡死，
 *       导致 LevelTwo/Mix（dobby / 混合）成了永不执行的死接线（审查 P2）。</li>
 *   <li>返回值语义是"是否真的启用了 native 重定向"，不是"有没有调用"：无原包、库未加载、
 *       native 返回 false 都必须是 false，XpInit 只能据此打日志，不能无条件写"已启用"。</li>
 *   <li>Java 层（{@link PMSignBypass} 的 PackageInfo/ApplicationPackageManager hook）由
 *       {@code XpInit.installSignBypass()} 统一安装，本类**不再**重复安装：对同一个 member
 *       再 doHook 会追加第二个 DispatchParam，PackageInfo(Parcel) 回调会跑两遍。</li>
 * </ol>
 */
public class SignBypass {

    private static final String TAG = "XpSignBypass";

    public static int normalizeType(int i) {
        if (i == 0 || i == 1 || i == 2 || i == 3) {
            return i;
        }
        return 1;
    }

    /**
     * 按 {@link AppEnv#sign_bypass_type} 分派层级实现。
     *
     * @return 是否真的装上了 native io 重定向；type=0 或层级自身降级时为 false
     */
    public static boolean enable() throws Exception {
        SignBypassLevel level;
        int type = AppEnv.sign_bypass_type;
        if (type == 1) {
            level = new LevelOneSignBypass();
        } else if (type == 2) {
            level = new LevelTwoSignBypass();
        } else if (type == 3) {
            level = new MixSignBypass();
        } else {
            XpHook.log0(TAG, "没有 native 层可启用（bypass=" + type + "），仅保留 Java 层");
            return false;
        }
        boolean enabled = level.enable();
        XpHook.log0(TAG, "native 层级结果：bypass=" + type + "，已启用=" + enabled);
        return enabled;
    }

    /**
     * 本包安装路径（规范化）。native 侧按整串比较 open 的实参，Java 侧也必须给规范形。
     *
     * @return null 表示 AppEnv.selfApk 未知
     */
    static String selfPath() {
        return FileLayerHooks.canonicalPath(AppEnv.selfApk);
    }

    /**
     * 重定向目标：规范化后还必须是真实存在的文件，否则一律视为"没有原包"。
     * （把 open 全指向一个不存在的路径会让应用从"证书不对"变成 FileNotFoundException。）
     *
     * @return 规范化的存在路径；否则 null
     */
    static String targetPath(String raw) {
        String canonical = FileLayerHooks.canonicalPath(raw);
        if (canonical == null) {
            return null;
        }
        return new File(canonical).isFile() ? canonical : null;
    }
}
