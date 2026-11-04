package io.xpatch.core.reflect;

import io.xpatch.hook.AppEnv;
import java.lang.reflect.Constructor;
import java.util.Arrays;

public class XClass {
    public static Class<?> load(String str) throws ClassNotFoundException {
        Class<?> clsLoadClass;
        try {
            clsLoadClass = AppEnv.appClassLoader.loadClass(str);
        } catch (Exception unused) {
            clsLoadClass = null;
        }
        return clsLoadClass == null ? Class.forName(str) : clsLoadClass;
    }

    public static Class loadEx(String str) {
        try {
            return load(str);
        } catch (Exception unused) {
            return null;
        }
    }

    public static Constructor<?> getInit(Class<?> cls, Class<?>... clsArr) throws NoSuchMethodException {
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == clsArr.length) {
                int i = 0;
                while (true) {
                    if (i < clsArr.length) {
                        if (!checkClass(parameterTypes[i], clsArr[i])) {
                            break;
                        }
                        i++;
                    } else {
                        constructor.setAccessible(true);
                        return constructor;
                    }
                }
            }
        }
        throw new NoSuchMethodException("No Instance for " + cls + "[" + Arrays.toString(clsArr) + "]");
    }

    public static Constructor<?> getInit2(Class<?> cls, int i) throws NoSuchMethodException {
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length == i) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new NoSuchMethodException("No Instance for " + cls + "[" + i + "]");
    }

    public static <T> T newInstance(Class<?> cls, Object... objArr) throws Exception {
        Class[] clsArr = new Class[objArr.length];
        for (int i = 0; i < objArr.length; i++) {
            Object obj = objArr[i];
            if (obj == null) {
                clsArr[i] = null;
            } else {
                clsArr[i] = obj.getClass();
            }
        }
        return (T) newInstance(cls, clsArr, objArr);
    }

    public static <T> T newInstance(Class<?> cls, Class<?>[] clsArr, Object... objArr) throws Exception {
        if (clsArr.length == 0) {
            return (T) cls.newInstance();
        }
        return (T) getInit(cls, clsArr).newInstance(objArr);
    }

    public static boolean checkClass(Class<?> cls, Class<?> cls2) {
        if (cls == null || cls2 == null || cls.equals(cls2) || cls.isAssignableFrom(hasType(cls2))) {
            return true;
        }
        return cls.isAssignableFrom(cls2);
    }

    private static Class<?> hasType(Class<?> cls) {
        try {
            if (cls.equals(Boolean.class)) {
                return Boolean.TYPE;
            }
            if (cls.equals(Integer.class)) {
                return Integer.TYPE;
            }
            if (cls.equals(Long.class)) {
                return Long.TYPE;
            }
            if (cls.equals(Byte.class)) {
                return Byte.TYPE;
            }
            if (cls.equals(Short.class)) {
                return Short.TYPE;
            }
            if (cls.equals(Float.class)) {
                return Float.TYPE;
            }
            if (cls.equals(Double.class)) {
                return Double.TYPE;
            }
            return cls.equals(Character.class) ? Character.TYPE : cls;
        } catch (Exception unused) {
            return null;
        }
    }

    public static <T> T newInstanceByRandomParam(Class<?> cls, Object... objArr) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> constructor2 : cls.getDeclaredConstructors()) {
            if (constructor2.getParameterCount() >= objArr.length) {
                constructor = constructor2;
            }
        }
        if (constructor != null) {
            Object[] objArr2 = new Object[constructor.getParameterCount()];
            System.arraycopy(objArr, 0, objArr2, 0, objArr.length);
            return (T) constructor.newInstance(objArr2);
        }
        throw new RuntimeException("No Instance for " + cls + ",param count more than: " + objArr.length);
    }
}
