package io.xpatch.core.signbypass;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;
import io.xpatch.hook.XpHook;
import java.lang.reflect.Method;

public final class DeferredSignHooks {

    private static final String TAG = "XpSigDefer";

    private static volatile boolean scheduled;
    private static volatile boolean installed;
    private static volatile XpHook.XpUnhook unhook;

    private DeferredSignHooks() {
    }

    public static synchronized void schedule() {
        if (scheduled) {
            return;
        }
        scheduled = true;
        try {
            Method target = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            unhook = XpHook.doHook(target, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    installAll();
                    param.proceed();
                }
            });
            XpHook.log0(TAG, "延后签名 hook 已排队：将在 Application.onCreate 之前安装"
                    + "（避开启动早期加载 ApplicationPackageManager 的 ANR）");
        } catch (Throwable th) {
            XpHook.log0(TAG, "延后签名 hook 排队失败（不影响其它签名伪装）：" + Log.getStackTraceString(th));
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
        unhookNow();
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
