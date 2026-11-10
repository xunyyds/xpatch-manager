package io.xpatch.core.signbypass;

import io.xpatch.hook.XpHook;

/**
 * 原包定位占位类。**不再合成 APK**（历史实现曾把 selfApk 去掉 xp/ 再换回
 * {@code xp/source.manifest} 重新打包，产出的"原包"没有 v2/v3 签名块、证书是 XPatch 的、
 * 还带着引导桩 classesN.dex，任何文件级自校验都会失败，审查 P1-1）。
 *
 * <p>文件层唯一合法的原包来自打包时内嵌的 {@code xp/source.apk}：
 * {@code XpInit.performXpConfig()} 把它释放成 {@code <dataDir>/cache/android_id/<crc>.apk}，
 * 置 {@code setWritable(false)}，并写入 {@link io.xpatch.hook.AppEnv#source_apk_path}。
 * 没有这个条目时没有任何办法在设备上还原出字节级原包，因此这里只返回 null，
 * 由 {@link FileLayerHooks#install()} 判定"文件层不启用"，让 codePath 保持真实安装路径。
 */
public final class SourceReconstructor {

    private static final String TAG = "XpSource";

    private SourceReconstructor() {
    }

    /**
     * 恒返回 null：不做任何文件合成，也不写磁盘。
     *
     * @return 永远 null；调用方（XpInit）应保持 AppEnv.source_apk_path 为空即"文件层不启用"
     */
    public static String rebuild() {
        XpHook.log0(TAG, "没有内嵌原包，文件层不启用");
        return null;
    }
}
