package io.xpatch.core.reflect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

public class XMethod {

    public interface XMethodFilter {
        boolean onMethod(Method method);
    }

    public static XMethodBuilder clz(String str) {
        XMethodBuilder xMethodBuilder = new XMethodBuilder();
        xMethodBuilder.clazz(XClass.loadEx(str));
        return xMethodBuilder;
    }

    public static XMethodBuilder clz(Class<?> cls) {
        XMethodBuilder xMethodBuilder = new XMethodBuilder();
        xMethodBuilder.clazz(cls);
        return xMethodBuilder;
    }

    public static XMethodBuilder obj(Object obj) {
        XMethodBuilder xMethodBuilder = new XMethodBuilder();
        xMethodBuilder.obj(obj);
        return xMethodBuilder;
    }

    public static class XMethodBuilder {
        private Class<?> clz;
        private XMethodFilter filter;
        private boolean ignoreParam;
        private boolean matchMore;
        private String name;
        private boolean noLoop;
        private Object obj;
        private final ArrayList<Class<?>> paramTypes;
        private Class<?> retType;

        private XMethodBuilder() {
            this.paramTypes = new ArrayList<>();
        }

        public <T> T invoke(Object... objArr) throws Exception {
            if (this.paramTypes.size() == 0) {
                for (Object obj : objArr) {
                    if (obj == null) {
                        this.paramTypes.add(null);
                    } else {
                        this.paramTypes.add(obj.getClass());
                    }
                }
            }
            Method method = get();
            if (Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                return (T) method.invoke(null, objArr);
            }
            Objects.requireNonNull(this.obj, "Can't invoke a virtual method in a null object.");
            method.setAccessible(true);
            return (T) method.invoke(this.obj, objArr);
        }

        public Method getNoException() {
            try {
                return get();
            } catch (NoSuchMethodException unused) {
                return null;
            }
        }

        public Method get() throws NoSuchMethodException {
            Class<?> cls;
            XMethodFilter xMethodFilter;
            Object obj;
            Class<?> cls2 = this.clz;
            if (cls2 == null && (obj = this.obj) != null) {
                cls2 = obj.getClass();
            }
            Objects.requireNonNull(cls2, "find method class can't be null (name = " + this.name + ", ret = " + this.retType + ", params = " + this.paramTypes);
            for (Class<?> superclass = cls2; superclass != null; superclass = superclass.getSuperclass()) {
                for (Method method : superclass.getDeclaredMethods()) {
                    String name = method.getName();
                    Class<?> returnType = method.getReturnType();
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    String str = this.name;
                    if ((str == null || name.equals(str)) && ((cls = this.retType) == null || returnType.equals(cls))) {
                        if (!this.ignoreParam) {
                            if (parameterTypes.length != this.paramTypes.size()) {
                                continue;
                            } else {
                                int i = 0;
                                while (true) {
                                    if (i < this.paramTypes.size()) {
                                        if (this.paramTypes.get(i) == null || parameterTypes[i].equals(this.paramTypes.get(i)) || (this.matchMore && XClass.checkClass(parameterTypes[i], this.paramTypes.get(i)))) {
                                            i++;
                                        }
                                    } else {
                                        xMethodFilter = this.filter;
                                        if (xMethodFilter != null) {
                                        }
                                        method.setAccessible(true);
                                        return method;
                                    }
                                }
                            }
                        } else {
                            xMethodFilter = this.filter;
                            if (xMethodFilter != null || xMethodFilter.onMethod(method)) {
                                method.setAccessible(true);
                                return method;
                            }
                        }
                    }
                }
                if (this.noLoop) {
                    break;
                }
            }
            throw new NoSuchMethodException("No such method(name=" + this.name + ",ret=" + this.retType + ",params=" + this.paramTypes + ") found in class " + cls2);
        }

        public XMethodBuilder name(String str) {
            this.name = str;
            return this;
        }

        public XMethodBuilder noLoop() {
            this.noLoop = true;
            return this;
        }

        public XMethodBuilder ret(Class<?> cls) {
            this.retType = cls;
            return this;
        }

        public XMethodBuilder matchMore() {
            this.matchMore = true;
            return this;
        }

        public XMethodBuilder param(Class<?>... clsArr) {
            this.paramTypes.addAll(Arrays.asList(clsArr));
            return this;
        }

        public XMethodBuilder ignoreParam() {
            this.ignoreParam = true;
            return this;
        }

        public void clazz(Class<?> cls) {
            this.clz = cls;
        }

        public void obj(Object obj) {
            this.obj = obj;
        }

        public XMethodBuilder filter(XMethodFilter xMethodFilter) {
            this.filter = xMethodFilter;
            return this;
        }
    }

    public static Method[] getAllMethod(String str, String str2) {
        Class clsLoadEx = XClass.loadEx(str);
        ArrayList arrayList = new ArrayList();
        while (true) {
            if (clsLoadEx != null) {
                for (Method method : clsLoadEx.getDeclaredMethods()) {
                    if (method.getName().equals(str2)) {
                        arrayList.add(method);
                    }
                }
                clsLoadEx = clsLoadEx.getSuperclass();
            } else {
                return (Method[]) arrayList.toArray(new Method[0]);
            }
        }
    }

    public static Filter_Param ParamFilter(int i, int i2, Class<?> cls) {
        return new Filter_Param(i, i2, cls);
    }

    private static class Filter_Param implements XMethodFilter {
        private final Class<?> clz;
        private final int count;
        private final int pos;

        public Filter_Param(int i, int i2, Class<?> cls) {
            this.count = i;
            this.pos = i2;
            this.clz = cls;
        }

        @Override
        public boolean onMethod(Method method) {
            return method.getParameterCount() == this.count && method.getParameterCount() > this.pos && method.getParameterTypes()[this.pos].equals(this.clz);
        }
    }
}
