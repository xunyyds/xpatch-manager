package io.xpatch.core.module;

public class RemoteModule {
    public String apkPath;
    public String packageName;
    public int useApiLevel;

    public String toString() {
        return "RemoteModule{packageName='" + this.packageName + "', apkPath='" + this.apkPath + "', useApiLevel=" + this.useApiLevel + '}';
    }
}
