package io.xpatch.hook;

import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.HashSet;

public abstract class XpHook {
    private static final String FRAMEWORK_LOG_MODULE = "_framework";
    private static HashSet<Member> hookedMethods = new HashSet<>();
    private static XpHook sXpHook;

    public interface XpCallback {
        void onHook(XpParam xpParam) throws Throwable;
    }

    public static abstract class XpUnhook {
        public abstract Member getTarget();

        public abstract void unhook();
    }

    public abstract void deopt(Member member) throws UnsupportedOperationException;

    public abstract boolean hook(Member member);

    public abstract Object invoke(Member member, Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    public static XpUnhook doHook(final Member member, int i, XpCallback xpCallback) {
        final XpHookDispatcher.DispatchParam dispatchParam = new XpHookDispatcher.DispatchParam();
        dispatchParam.priority = i;
        dispatchParam.callback = xpCallback;
        if (doHook0(member)) {
            XpHookDispatcher.sortAndAdd(member, dispatchParam);
            return new XpUnhook() {
                @Override
                public void unhook() {
                    XpHookDispatcher.removeParam(member, dispatchParam);
                }

                @Override
                public Member getTarget() {
                    return member;
                }
            };
        }
        log0("XpHook", "hook 失败：" + member);
        return null;
    }

    private static boolean doHook0(Member member) {
        if (sXpHook == null) {
            return false;
        }
        if (hookedMethods.contains(member)) {
            return true;
        }
        if (!sXpHook.hook(member)) {
            return false;
        }
        hookedMethods.add(member);
        return true;
    }

    public static Object invoke0(Member member, Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        XpHook xpHook = sXpHook;
        if (xpHook == null) {
            throw new UnsupportedOperationException("hook 内核尚未就绪");
        }
        return xpHook.invoke(member, obj, objArr);
    }

    public static Object invoke0(Member member, int i, Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (sXpHook == null) {
            throw new UnsupportedOperationException("hook 内核尚未就绪");
        }
        return XpHookDispatcher.invoke(member, i, obj, objArr);
    }

    public static void deopt0(Member member) throws UnsupportedOperationException {
        XpHook xpHook = sXpHook;
        if (xpHook == null) {
            throw new UnsupportedOperationException("hook 内核尚未就绪");
        }
        xpHook.deopt(member);
    }

    public static void setXpHook(XpHook xpHook) {
        sXpHook = xpHook;
    }

    public static String normalizeLogModulePackage(String str) {
        return (str == null || str.trim().isEmpty()) ? FRAMEWORK_LOG_MODULE : str.trim();
    }

    public static void log0(String str, String str2) {
        logModule0(null, str, str2);
    }

    public static void logModule0(String str, String str2, String str3) {
        Log.i("XPatch", "XPatch 日志：" + str2 + " -> " + str3);
        LogSupport.writeLine(normalizeLogModulePackage(str), str2 + " -> " + str3);
    }

    public static abstract class XpParam {
        public Object[] args;
        public Object[] context;
        public Member member;
        private ProceedImpl proceedImpl;
        private boolean resolved;
        private boolean skipped;
        public Member target;
        public Object this0;

        interface ProceedImpl {
            Object proceed() throws Throwable;
        }

        public abstract Object getResult();

        public abstract Throwable getThrowable();

        protected abstract Object invokeOriginalMethod() throws Throwable;

        public abstract void setResult(Object obj);

        public abstract void setThrowable(Throwable th);

        public final Object proceed() throws Throwable {
            this.resolved = true;
            ProceedImpl proceedImpl = this.proceedImpl;
            if (proceedImpl == null) {
                return invokeOriginalMethod();
            }
            return proceedImpl.proceed();
        }

        public final boolean isSkipped() {
            return this.skipped;
        }

        protected final void setSkipped(boolean z) {
            this.skipped = z;
        }

        protected final void markResolved() {
            this.resolved = true;
        }

        public final boolean isResolved() {
            return this.resolved;
        }

        final ProceedImpl replaceProceedImpl(ProceedImpl proceedImpl) {
            ProceedImpl proceedImpl2 = this.proceedImpl;
            this.proceedImpl = proceedImpl;
            return proceedImpl2;
        }
    }
}
