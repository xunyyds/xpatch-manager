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

    private static final List<Runnable> startupCallbacks = new ArrayList<Runnable>();

    private DeferredSignHooks() {
    }

    public static synchronized boolean schedule() {
        if (scheduled) {
            return true;
        }
        try {
            Method target = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            XpHook.XpUnhook token = XpHook.doHook(target, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    installAll();
                    param.proceed();
                    markStartupComplete();
                    unhookLater();
                }
            });
            if (token == null) {
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
    }

    private static void unhookLater() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    unhookNow();
                }
            });
        } catch (Throwable th) {
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
