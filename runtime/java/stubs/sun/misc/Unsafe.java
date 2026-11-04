package sun.misc;

import java.lang.reflect.Field;

public final class Unsafe {
public long objectFieldOffset(Field field) {
throw new UnsupportedOperationException();
}

public long getLong(Object obj, long offset) {
throw new UnsupportedOperationException();
}

public long getLong(long address) {
throw new UnsupportedOperationException();
}

public int getInt(long address) {
throw new UnsupportedOperationException();
}

public void putLong(Object obj, long offset, long value) {
throw new UnsupportedOperationException();
}

public void putObject(Object obj, long offset, Object value) {
throw new UnsupportedOperationException();
}
}
