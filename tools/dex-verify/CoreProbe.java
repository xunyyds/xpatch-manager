import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

public class CoreProbe {

    public static void main(String[] args) throws Exception {
        File so = new File(args[0]);

        Class<?> bridge = Class.forName("io.xpatch.core.entry.NativeHookBridge");
        System.out.println("step1 bridge cl=" + bridge.getClassLoader());

        System.load(so.getAbsolutePath());
        System.out.println("step2 so loaded");

        Method hook = bridge.getMethod("hookMethod", Executable.class, Class.class);
        Method target = Target.class.getDeclaredMethod("value", int.class);
        System.out.println("step3 target=" + target);
        boolean ok = (Boolean) hook.invoke(null, target, Callback.class);
        System.out.println("step4 hook=" + ok);

        Target instance = new Target();
        int got = instance.value(41);
        System.out.println("step5 value(41)=" + got);
        if (got != 4242) {
            throw new IllegalStateException("hook did not take effect, got " + got);
        }
        System.out.println("ALL OK");
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
