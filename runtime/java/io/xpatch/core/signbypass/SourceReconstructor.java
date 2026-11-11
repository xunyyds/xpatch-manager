package io.xpatch.core.signbypass;

import io.xpatch.hook.XpHook;

public final class SourceReconstructor {

    private static final String TAG = "XpSource";

    private SourceReconstructor() {
    }

    public static String rebuild() {
        XpHook.log0(TAG, "没有内嵌原包，文件层不启用");
        return null;
    }
}
