package io.xpatch.core.signbypass;

import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class SourceReconstructor {

    private static final String TAG = "XpSource";
    private static final String MANIFEST = "xp/source.manifest";

    private SourceReconstructor() {
    }

    public static String rebuild() {
        if (AppEnv.selfApk == null || AppEnv.baseApp == null) {
            return null;
        }
        try {
            ZipFile zip = new ZipFile(AppEnv.selfApk);
            try {
                ZipEntry manifest = zip.getEntry(MANIFEST);
                if (manifest == null) {
                    return null;
                }
                File out = new File(AppEnv.baseApp.dataDir + "/cache/android_id/source-"
                        + System.currentTimeMillis() + ".apk");
                out.getParentFile().mkdirs();
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
                try {
                    byte[] buffer = new byte[64 * 1024];
                    for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                        if (entry.isDirectory() || entry.getName().startsWith("xp/")) {
                            continue;
                        }
                        ZipEntry copy = new ZipEntry(entry.getName());
                        zos.putNextEntry(copy);
                        if ("AndroidManifest.xml".equals(entry.getName())) {
                            zos.write(readAll(zip.getInputStream(manifest), buffer));
                        } else {
                            InputStream in = zip.getInputStream(entry);
                            int n;
                            while ((n = in.read(buffer)) > 0) {
                                zos.write(buffer, 0, n);
                            }
                            in.close();
                        }
                        zos.closeEntry();
                    }
                } finally {
                    zos.close();
                }
                XpHook.log0(TAG, "原包按需重建完成：" + out.getAbsolutePath());
                return out.getAbsolutePath();
            } finally {
                zip.close();
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "原包重建失败：" + android.util.Log.getStackTraceString(th));
            return null;
        }
    }

    private static byte[] readAll(InputStream in, byte[] buffer) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
