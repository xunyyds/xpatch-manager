import java.lang.reflect.Executable;
import java.lang.reflect.Method;

public class NativeProbe {

    public static void main(String[] args) throws Exception {
        Class<?> bridge = Class.forName("io.xpatch.core.entry.NativeHookBridge");
        System.load(args[0]);

        Method hook = bridge.getMethod("hookMethod", Executable.class, Class.class);
        System.out.println("A got hookMethod");

        Method target = Target.class.getDeclaredMethod("value", int.class);
        System.out.println("B target=" + target);

        Object result = hook.invoke(null, target, Callback.class);
        System.out.println("C result=" + result);
    }

    public static class Target {
        public int value(int input) {
            return input + 1;
        }
    }

    public static class Callback {
        public Object backup;

        public Callback(Executable target) {
        }

        public Object callback(Object[] args) {
            return 4242;
        }
    }
}
