package io.xpatch;

import android.os.ParcelFileDescriptor;

interface IXPatchService {

    void destroy() = 16777114;

    String exec(String command) = 1;

    String readText(String path, int maxBytes) = 2;

    void forceStop(String packageName) = 3;

    void dexopt(String packageName) = 4;

    int getUid() = 5;

    ParcelFileDescriptor openRead(String path) = 6;
}
