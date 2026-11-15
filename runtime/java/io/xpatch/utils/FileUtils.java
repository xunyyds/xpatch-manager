package io.xpatch.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileUtils {
    public static void write(String str, byte[] bArr) {
        File file = new File(str);
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }
        File backup = new File(str + ".bak");
        File tmp = new File(str + ".tmp");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(tmp);
            try {
                fileOutputStream.write(bArr);
                fileOutputStream.flush();
                fileOutputStream.getFD().sync();
            } finally {
                fileOutputStream.close();
            }
            if (file.isFile() && !backup.exists() && !file.renameTo(backup)) {
                backup = null;
            }
            if (!tmp.renameTo(file)) {
                if (!tmp.renameTo(file)) {
                    throw new IOException("rename 到 " + file + " 失败");
                }
            }
        } catch (IOException e) {
            if (tmp.exists()) {
                tmp.delete();
            }
            throw new RuntimeException(e);
        }
    }

    public static void write(String str, String str2) {
        write(str, str2.getBytes());
    }

    public static byte[] readBytes(String str) {
        return readBytes(new File(str));
    }

    public static byte[] readBytes(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            try {
                byte[] bArr = new byte[(int) file.length()];
                int offset = 0;
                while (offset < bArr.length) {
                    int read = fileInputStream.read(bArr, offset, bArr.length - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset < bArr.length) {
                    byte[] shorter = new byte[offset];
                    System.arraycopy(bArr, 0, shorter, 0, offset);
                    return shorter;
                }
                return bArr;
            } finally {
                fileInputStream.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] readWithFallback(String str) {
        File primary = new File(str);
        byte[] bytes = readBytes(primary);
        if (bytes != null) {
            return bytes;
        }
        return readBytes(new File(str + ".bak"));
    }

    public static String readString(String str) {
        byte[] bytes = readWithFallback(str);
        if (bytes == null) {
            return null;
        }
        return new String(bytes);
    }

    public static boolean isPathWriteable(String str) {
        File file = new File(str);
        if (file.isFile()) {
            return false;
        }
        if (!file.exists()) {
            return file.mkdirs();
        }
        File file2 = new File(file, ".write.checker");
        if (file2.exists()) {
            return file2.delete();
        }
        try {
            if (file2.createNewFile()) {
                return file2.delete();
            }
            return false;
        } catch (IOException unused) {
            return false;
        }
    }

    public static boolean delete(File file) {
        if (file == null) {
            return false;
        }
        if (file.isFile()) {
            return file.delete();
        }
        File[] fileArrListFiles = file.listFiles();
        if (fileArrListFiles == null) {
            return true;
        }
        for (File file2 : fileArrListFiles) {
            delete(file2);
        }
        return file.delete();
    }

    public static void copy(String str, String str2) {
        File file = new File(str);
        File file2 = new File(str2);
        if (!file2.getParentFile().exists()) {
            file2.getParentFile().mkdirs();
        }
        if (file.isDirectory()) {
            if (!file2.exists()) {
                file2.mkdirs();
            }
            File[] fileArrListFiles = file.listFiles();
            if (fileArrListFiles == null) {
                return;
            }
            for (File file3 : fileArrListFiles) {
                copy(file3.getAbsolutePath(), str2 + File.separator + file3.getName());
            }
            return;
        }
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            byte[] bArr = new byte[1024];
            while (true) {
                int i = fileInputStream.read(bArr);
                if (i != -1) {
                    fileOutputStream.write(bArr, 0, i);
                } else {
                    fileInputStream.close();
                    fileOutputStream.close();
                    return;
                }
            }
        } catch (IOException unused) {
        }
    }
}
