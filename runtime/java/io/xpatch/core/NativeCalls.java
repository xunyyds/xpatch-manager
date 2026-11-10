package io.xpatch.core;

/**
 * hook_core.so 的 JNI 桥（libc 层 open/openat/fopen 路径重定向）。
 *
 * <p>不变量：
 * <ol>
 *   <li>这些 native 方法只有在 {@link NativeLoader#load} 成功后才可用；未加载时调用必抛
 *       UnsatisfiedLinkError，所以调用方必须先查 {@link #loaded()}（文件层三个层级类已这样做）。</li>
 *   <li>方法签名与 .so 里的导出符号一一对应，不能随意改返回类型：
 *       {@code Java_io_xpatch_core_NativeCalls_enable_1io_1redirect}、
 *       {@code ..._enable_1io_1redirect_1dobby}（native 侧返回 void，Java 侧必须保持 void，
 *       否则会读到无意义的寄存器值）、{@code ..._enable_1io_1redirect_1mix}、
 *       {@code ..._setCookieTrusted}。</li>
 *   <li>返回 boolean 的两个入口语义是"是否真的 hook 上了函数"（native 侧 {@code install()}
 *       返回 {@code hooked > 0}），false 表示一个函数都没挂上，属于降级，必须让上层看见。</li>
 * </ol>
 */
public class NativeCalls {
    /** 启用 libc open/openat/fopen 的路径重定向；true=hooked 数大于 0。 */
    public static native boolean enable_io_redirect(String str, String str2);

    /**
     * dobby 版重定向。native 导出返回 void，拿不到 hooked 计数：
     * 只能用"调用未抛异常"近似当作已提交，日志里要说明这一点。
     */
    public static native void enable_io_redirect_dobby(String str, String str2);

    /** 混合（dobby + inline）重定向；true=hooked 数大于 0。 */
    public static native boolean enable_io_redirect_mix(String str, String str2);

    public static native void setCookieTrusted(Object obj);

    /** hook_core.so 是否已由 {@link NativeLoader} 在本进程加载成功。 */
    public static boolean loaded() {
        return NativeLoader.isLoaded();
    }

    /** 已加载的 .so 路径（诊断用；文件通常已被删除）。 */
    public static String libraryPath() {
        return NativeLoader.loadedPath();
    }
}
