package io.xpatch.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

public final class InvokeHelper {
    private final Executable executable;

    private static native Object invokeSpecialNative(Executable executable, Object obj, Object[] objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException;

    public InvokeHelper(Executable executable) {
        this.executable = (Executable) Objects.requireNonNull(executable, "executable");
    }

    public Executable getExecutable() {
        return this.executable;
    }

    public Object invokeSpecial(Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invokeSpecial(this.executable, obj, objArr);
    }

    public static Object invokeSpecial(Executable executable, Object obj, Object... objArr) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        MethodHandle methodHandleTryCreateSpecialHandle;
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(obj, "thisObject");
        if (Modifier.isStatic(executable.getModifiers())) {
            throw new IllegalArgumentException("invokeSpecial does not support static executable: " + executable);
        }
        if (!executable.getDeclaringClass().isInstance(obj)) {
            throw new IllegalArgumentException("thisObject " + obj.getClass().getName() + " is not assignable to " + executable.getDeclaringClass().getName());
        }
        if (objArr == null) {
            objArr = new Object[0];
        }
        if ((executable instanceof Method) && (methodHandleTryCreateSpecialHandle = tryCreateSpecialHandle((Method) executable, obj)) != null) {
            try {
                return methodHandleTryCreateSpecialHandle.invokeWithArguments(objArr);
            } catch (ClassCastException e) {
                throw e;
            } catch (IllegalArgumentException e2) {
                throw e2;
            } catch (WrongMethodTypeException e3) {
                throw e3;
            } catch (Throwable th) {
                throw new InvocationTargetException(th);
            }
        }
        return invokeSpecialNative(executable, obj, objArr);
    }

    private static MethodHandle tryCreateSpecialHandle(Method method, Object obj) throws IllegalAccessException {
        try {
            MethodHandles.Lookup lookupCreatePrivateLookup = createPrivateLookup(method.getDeclaringClass());
            if (lookupCreatePrivateLookup == null) {
                return null;
            }
            return lookupCreatePrivateLookup.unreflectSpecial(method, method.getDeclaringClass()).bindTo(obj);
        } catch (ReflectiveOperationException | SecurityException | UnsupportedOperationException unused) {
            return null;
        }
    }

    private static MethodHandles.Lookup createPrivateLookup(Class<?> cls) throws ReflectiveOperationException {
        return (MethodHandles.Lookup) MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class).invoke(null, cls, MethodHandles.lookup());
    }
}
