import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DexVerify {

    private static final String[] CLASSES = {
            "io.xpatch.core.CoreEntry",
            // 注入路径混淆（docs/inject-path-obfuscation.md）的布局解析：它在引导期被 get()/configEntry()
            // 直接调到，是「dex 结构 / 常量池 / 正则常量」出问题时最先炸的类，必须纳入校验。
            "io.xpatch.core.XpLayout",
            "io.xpatch.hook.XpHelper",
            "io.xpatch.core.XpInit",
            "io.xpatch.core.module.ModuleLoader",
            "io.xpatch.core.SimpleClassLoader",
            "io.xpatch.hook.XpHook",
            "io.xpatch.hook.XpHookDispatcher",
    };

    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        System.out.println("dex bytes=" + bytes.length);

        Class<?> loaderClass = Class.forName("dalvik.system.InMemoryDexClassLoader");
        Constructor<?> ctor = loaderClass.getConstructor(
                ByteBuffer[].class, String.class, ClassLoader.class);
        ClassLoader loader = (ClassLoader) ctor.newInstance(
                new ByteBuffer[]{ByteBuffer.wrap(bytes)}, null, ClassLoader.getSystemClassLoader());
        System.out.println("loader=" + loader);

        for (String name : CLASSES) {
            Class<?> cls = Class.forName(name, true, loader);
            System.out.println("verified+init: " + cls.getName());
        }
        System.out.println("ALL OK");
    }
}
