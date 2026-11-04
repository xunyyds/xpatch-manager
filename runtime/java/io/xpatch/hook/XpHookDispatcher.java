package io.xpatch.hook;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class XpHookDispatcher {
    protected static final ConcurrentHashMap<Member, ArrayList<DispatchParam>> dispatchMap = new ConcurrentHashMap<>();

    public static class DispatchParam {
        public XpHook.XpCallback callback;
        public int priority;
    }

    static ArrayList lambda$sortAndAdd$0(Member member) {
        return new ArrayList();
    }

    public static void sortAndAdd(Member member, DispatchParam dispatchParam) {
        ArrayList<DispatchParam> arrayListComputeIfAbsent = dispatchMap.computeIfAbsent(member, new Function() {
            @Override
            public final Object apply(Object obj) {
                return XpHookDispatcher.lambda$sortAndAdd$0((Member) obj);
            }
        });
        synchronized (arrayListComputeIfAbsent) {
            for (int i = 0; i < arrayListComputeIfAbsent.size(); i++) {
                if (arrayListComputeIfAbsent.get(i).priority < dispatchParam.priority) {
                    arrayListComputeIfAbsent.add(i, dispatchParam);
                    return;
                }
            }
            arrayListComputeIfAbsent.add(dispatchParam);
        }
    }

    public static void removeParam(Member member, DispatchParam dispatchParam) {
        ArrayList<DispatchParam> arrayList = dispatchMap.get(member);
        if (arrayList != null) {
            synchronized (arrayList) {
                arrayList.remove(dispatchParam);
            }
        }
    }

    public static Object invoke(Member member, int i, Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        ArrayList<DispatchParam> arrayList = dispatchMap.get(member);
        if (arrayList == null) {
            return XpHook.invoke0(member, obj, objArr);
        }
        ArrayList arrayList2 = new ArrayList();
        synchronized (arrayList) {
            for (DispatchParam dispatchParam : arrayList) {
                if (dispatchParam.priority <= i) {
                    arrayList2.add(dispatchParam);
                }
            }
        }
        if (arrayList2.isEmpty()) {
            return XpHook.invoke0(member, obj, objArr);
        }
        try {
            return dispatchInternal(new InvokeParam(member, obj, objArr), arrayList2, 0);
        } catch (IllegalAccessException e) {
            throw e;
        } catch (IllegalArgumentException e2) {
            throw e2;
        } catch (InvocationTargetException e3) {
            throw e3;
        } catch (Throwable th) {
            throw new InvocationTargetException(th);
        }
    }

    public static void dispatch(XpHook.XpParam xpParam) throws Throwable {
        ArrayList arrayList;
        ArrayList<DispatchParam> arrayList2 = dispatchMap.get(xpParam.target);
        if (arrayList2 != null) {
            synchronized (arrayList2) {
                arrayList = new ArrayList(arrayList2);
            }
            dispatchInternal(xpParam, arrayList, 0);
        }
    }

    public static Object dispatchInternal(final XpHook.XpParam xpParam, final List<DispatchParam> list, final int i) throws Throwable {
        if (i >= list.size()) {
            xpParam.markResolved();
            return xpParam.invokeOriginalMethod();
        }
        XpHook.XpParam.ProceedImpl proceedImplReplaceProceedImpl = xpParam.replaceProceedImpl(new XpHook.XpParam.ProceedImpl() {
            @Override
            public final Object proceed() throws Throwable {
                return XpHookDispatcher.dispatchInternal(xpParam, list, i + 1);
            }
        });
        try {
            list.get(i).callback.onHook(xpParam);
            xpParam.replaceProceedImpl(proceedImplReplaceProceedImpl);
            if (!xpParam.isResolved() && !xpParam.isSkipped() && xpParam.getThrowable() == null) {
                return xpParam.proceed();
            }
            if (xpParam.getThrowable() != null) {
                throw xpParam.getThrowable();
            }
            return xpParam.getResult();
        } catch (Throwable th) {
            xpParam.replaceProceedImpl(proceedImplReplaceProceedImpl);
            throw th;
        }
    }

    private static class InvokeParam extends XpHook.XpParam {
        private Object result;
        private Throwable throwable;

        private InvokeParam(Member member, Object obj, Object[] objArr) {
            this.member = member;
            this.target = member;
            this.this0 = obj;
            this.args = objArr;
        }

        @Override
        public Object getResult() {
            return this.result;
        }

        @Override
        public void setResult(Object obj) {
            this.result = obj;
            setSkipped(true);
            markResolved();
        }

        @Override
        public void setThrowable(Throwable th) {
            this.throwable = th;
            markResolved();
        }

        @Override
        public Throwable getThrowable() {
            return this.throwable;
        }

        @Override
        protected Object invokeOriginalMethod() throws Throwable {
            return XpHook.invoke0(this.member, this.this0, this.args);
        }
    }
}
