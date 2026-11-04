package io.xpatch.core;

public class NativeCalls {
    public static native boolean enable_io_redirect(String str, String str2);

    public static native void enable_io_redirect_dobby(String str, String str2);

    public static native boolean enable_io_redirect_mix(String str, String str2);

    public static native void setCookieTrusted(Object obj);
}
