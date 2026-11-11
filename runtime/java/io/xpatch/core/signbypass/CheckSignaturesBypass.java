package io.xpatch.core.signbypass;

import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;
import io.xpatch.core.reflect.XClass;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.lang.reflect.Method;

public final class CheckSignaturesBypass {

    private static final String TAG = "XpSigCheck";

    private static final int MAX_ATTEMPTS = 2;

    private static volatile int attempts;
    private static volatile boolean installed;
    private static volatile boolean hitLogged;

    private CheckSignaturesBypass() {
    }

    public static synchronized boolean installNow() {
        if (installed) {
            return true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }
        attempts++;
        boolean hooked = false;
        Class<?> manager = XClass.loadEx("android.app.ApplicationPackageManager");
        if (manager != null) {
            hooked |= hookCheckSignatures(manager, new Class[]{String.class, String.class});
            hooked |= hookCheckSignatures(manager, new Class[]{Integer.TYPE, Integer.TYPE});
        } else {
            XpHook.log0(TAG, "找不到 ApplicationPackageManager，checkSignatures 伪装跳过");
        }
        installed = hooked;
        XpHook.log0(TAG, hooked
                ? "checkSignatures 伪装已安装：本包（包名/uid 两种重载）与任意包比较返回 MATCH"
                : "checkSignatures 伪装未安装（应用若用它自校验可能失败，其它签名伪装不受影响）");
        return hooked;
    }

    private static boolean hookCheckSignatures(Class<?> manager, Class<?>[] params) {
        try {
            Method method = manager.getMethod("checkSignatures", params);
            return XpHook.doHook(method, 0, new CheckSignaturesCallback()) != null;
        } catch (Throwable th) {
            XpHook.log0(TAG, "hook " + manager.getName() + ".checkSignatures(" + describeParams(params)
                    + ") 失败：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static String describeParams(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private static boolean isSelf(Object value) {
        if (value instanceof String) {
            return AppEnv.packageName != null && AppEnv.packageName.equals(value);
        }
        if (value instanceof Integer) {
            return ((Integer) value).intValue() == Process.myUid();
        }
        return false;
    }

    private static void logHit(Object first, Object second) {
        if (hitLogged) {
            return;
        }
        hitLogged = true;
        XpHook.log0(TAG, "checkSignatures 首次命中：(" + first + ", " + second + ") -> MATCH");
    }

    private static final class CheckSignaturesCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam param) throws Throwable {
            Object[] args = param.args;
            if (args == null || args.length < 2) {
                param.proceed();
                return;
            }
            if (isSelf(args[0]) || isSelf(args[1])) {
                logHit(args[0], args[1]);
                param.setResult(Integer.valueOf(PackageManager.SIGNATURE_MATCH));
                return;
            }
            param.proceed();
        }
    }
}
