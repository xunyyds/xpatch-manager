package io.xpatch.core;

import dalvik.system.InMemoryDexClassLoader;
import java.nio.ByteBuffer;

public class SimpleClassLoader {
    public static ClassLoader loadDex(ByteBuffer[] byteBufferArr, ClassLoader classLoader) {
        return new InMemoryDexClassLoader(byteBufferArr, null, classLoader);
    }
}
