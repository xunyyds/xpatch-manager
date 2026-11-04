package org.lsposed.hidden;

import dalvik.system.PathClassLoader;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Executable;

final class CoreOjClassLoader extends PathClassLoader {
    static final boolean $assertionsDisabled = false;

    private static String getCoreOjPath() {
        return System.getProperty("java.boot.class.path", "").split(":", 2)[0];
    }

    CoreOjClassLoader() {
        super(getCoreOjPath(), null);
    }

    @Override
    public Class<?> loadClass(String str) throws ClassNotFoundException {
        if (Object.class.getName().equals(str)) {
            return Object.class;
        }
        try {
            return findClass(str);
        } catch (ClassNotFoundException unused) {
            if (Executable.class.getName().equals(str)) {
                return Helper.Executable.class;
            }
            if (MethodHandle.class.getName().equals(str)) {
                return Helper.MethodHandle.class;
            }
            if (Class.class.getName().equals(str)) {
                return Helper.Class.class;
            }
            return super.loadClass(str);
        }
    }
}
