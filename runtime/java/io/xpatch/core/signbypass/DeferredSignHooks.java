package io.xpatch.core.signbypass;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;
import io.xpatch.hook.XpHook;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class DeferredSignHooks {

    private static final String TAG = "XpSigDefer";

    private static volatile boolean scheduled;
    private static volatile boolean installed;
    private static volatile boolean startupCompleted;
    private static volatile XpHook.XpUnhook unhook;

    /**
     * 启动完成回调（W1 用它删除 pending 文件）：
     * 只在 callApplicationOnCreate 的 {@code param.proceed()} 正常返回之后跑一次。
     */
    private static final List<Runnable> startupCallbacks = new ArrayList<Runnable>();

    private DeferredSignHooks() {
    }

    /**
     * 排队"延后签名 hook"：在 {@code Instrumentation.callApplicationOnCreate} 上挂一次性钩子。
     *
     * <p>返回值契约（P1 回归修复）：
     * <ul>
     *   <li>{@code true}：钩子确实挂上了。启动完成回调会在 {@code Application.onCreate}
     *       正常返回之后由 {@link #markStartupComplete()} 执行。</li>
     *   <li>{@code false}：没挂上（{@code doHook} 返回 null 或抛异常）。此时只做
     *       {@code installAll()} 让签名层尽量可用，<b>绝不</b>{@code markStartupComplete()}：
     *       一旦提前置真，调用方（W1）稍后注册的"启动完成"回调会同步立即执行，
     *       pending 文件刚写就被删，模块崩溃熔断彻底失效。调用方必须据此装自己的兜底钩子。</li>
     * </ul>
     */
    public static synchronized boolean schedule() {
        if (scheduled) {
            return true;
        }
        try {
            Method target = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            XpHook.XpUnhook token = XpHook.doHook(target, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    // 顺序不变量（不可交换）：
                    //   1) installAll() 先把签名/PM 层 hook 补齐（此时 Application.onCreate 还没跑）；
                    //   2) proceed() 才是真正的 Application.onCreate，它是"启动"的全部风险区；
                    //   3) 只有 proceed() 正常返回才 markStartupComplete()。
                    // proceed() 抛异常（模块/应用在 onCreate 里崩溃）时这里不 mark：
                    // W1 的 pending 文件留到下次启动，由 loadModuleCrashCounts() 计一次崩溃。
                    installAll();
                    param.proceed();
                    markStartupComplete();
                    // 钩子本身要等这次调用返回后再摘（不能在执行中 unhook 当前 Member）
                    unhookLater();
                }
            });
            if (token == null) {
                // 不能声称"已排队"：回滚 scheduled，至少让签名层立即可用（避免整层钩子静默消失）。
                // 不 markStartupComplete()：没有钩子就没有"onCreate 正常返回"这个事实，
                // 启动完成只能由调用方的兜底钩子（proceed 之后）来置真。
                scheduled = false;
                XpHook.log0(TAG, "延后签名 hook 安装失败（doHook 返回 null），改为立即安装；"
                        + "启动完成回调需由兜底钩子（proceed 之后）执行");
                installAll();
                unhookNow();
                return false;
            }
            unhook = token;
            scheduled = true;
            XpHook.log0(TAG, "延后签名 hook 已排队：将在 Application.onCreate 之前安装"
                    + "（避开启动早期加载 ApplicationPackageManager 的 ANR），"
                    + "启动完成回调在 onCreate 正常返回之后执行");
            return true;
        } catch (Throwable th) {
            scheduled = false;
            XpHook.log0(TAG, "延后签名 hook 排队失败，改为立即安装（不影响其它签名伪装）："
                    + Log.getStackTraceString(th));
            installAll();
            unhookNow();
            return false;
        }
    }

    /**
     * 注册"启动完成"回调（幂等：同一 Runnable 重复注册只保留一份）。
     * 只在 {@link #markStartupComplete()} 之后执行；若调用时启动已经完成，回调会同步执行一次。
     */
    public static void onStartupComplete(Runnable callback) {
        if (callback == null) {
            return;
        }
        boolean runNow;
        synchronized (startupCallbacks) {
            if (startupCompleted) {
                runNow = true;
            } else {
                if (!startupCallbacks.contains(callback)) {
                    startupCallbacks.add(callback);
                }
                runNow = false;
            }
        }
        if (runNow) {
            runQuietly(callback);
        }
    }

    /**
     * 置位"启动完成"并执行全部回调（幂等，异常只记日志）。
     *
     * <p><b>唯一合法调用点</b>是 {@code callApplicationOnCreate} 回调里 {@code param.proceed()}
     * <b>之后</b>：启动完成等价于"Application.onCreate 正常返回"。任何在 proceed 之前或
     * 在 hook 安装失败时调用它的地方，都会让 W1 的 pending 删除提前，熔断失效（P1 回归）。
     */
    public static void markStartupComplete() {
        List<Runnable> pending;
        synchronized (startupCallbacks) {
            if (startupCompleted) {
                return;
            }
            startupCompleted = true;
            pending = new ArrayList<Runnable>(startupCallbacks);
            startupCallbacks.clear();
        }
        for (Runnable callback : pending) {
            runQuietly(callback);
        }
    }

    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable th) {
            XpHook.log0(TAG, "启动完成回调执行失败：" + Log.getStackTraceString(th));
        }
    }

    private static synchronized void installAll() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            SignatureFacade.retryIfNeeded();
        } catch (Throwable th) {
            XpHook.log0(TAG, "签名原语层重试失败：" + Log.getStackTraceString(th));
        }
        try {
            PMSignBypass.installDeferredHooks();
        } catch (Throwable th) {
            XpHook.log0(TAG, "getPackageArchiveInfo 重定向安装失败：" + Log.getStackTraceString(th));
        }
        try {
            CheckSignaturesBypass.installNow();
        } catch (Throwable th) {
            XpHook.log0(TAG, "checkSignatures 伪装安装失败：" + Log.getStackTraceString(th));
        }
        try {
            PMSignBypass.installEarly();
        } catch (Throwable th) {
            XpHook.log0(TAG, "hasSigningCertificate hook 安装失败：" + Log.getStackTraceString(th));
        }
        // 不在这里 unhook：installAll() 正是从 callApplicationOnCreate 的钩子回调里调用的，
        // 当前 Member 还在执行栈上；直接摘钩子可能释放仍在执行的 trampoline。摘钩子由
        // 回调在 proceed() 返回后 post 到主线程执行（见 unhookLater）。
    }

    /**
     * 把摘钩子推迟到当前 callApplicationOnCreate 调用返回之后（主线程下一个消息）。
     * 不能在钩子执行期间直接 unhook 当前 Member。
     */
    private static void unhookLater() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    unhookNow();
                }
            });
        } catch (Throwable th) {
            // 主线程 Looper 不可用（极少见）时退化为直接摘；至少不阻塞启动。
            unhookNow();
        }
    }

    private static void unhookNow() {
        XpHook.XpUnhook target = unhook;
        unhook = null;
        if (target == null) {
            return;
        }
        try {
            target.unhook();
            XpHook.log0(TAG, "延后签名 hook 安装完成，已摘掉一次性钩子");
        } catch (Throwable ignored) {
        }
    }
}
