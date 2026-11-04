package io.xpatch.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DataUtils {
    public static byte[] objectToBytes(Object obj) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(obj);
            objectOutputStream.close();
            return gzipCompress(byteArrayOutputStream.toByteArray());
        } catch (IOException unused) {
            return new byte[0];
        }
    }

    public static byte[] deflateCompress(byte[] bArr) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(byteArrayOutputStream, new Deflater());
            deflaterOutputStream.write(bArr);
            deflaterOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static byte[] deflateUncompress(byte[] bArr) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            InflaterOutputStream inflaterOutputStream = new InflaterOutputStream(byteArrayOutputStream, new Inflater());
            inflaterOutputStream.write(bArr);
            inflaterOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static Object bytesToObject(byte[] bArr) {
        try {
            return new ObjectInputStream(new ByteArrayInputStream(gzipUncompress(bArr))).readObject();
        } catch (Exception unused) {
            return null;
        }
    }

    public static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] bArr = new byte[4096];
        while (true) {
            int i = inputStream.read(bArr);
            if (i == -1) {
                return byteArrayOutputStream.toByteArray();
            }
            byteArrayOutputStream.write(bArr, 0, i);
        }
    }

    public static String convertBytesFix(long j) {
        if (j < 1024) {
            return j + " B";
        }
        double d = j;
        int iLog = (int) (Math.log(d) / Math.log(1024.0d));
        char cCharAt = "KMGTPE".charAt(iLog - 1);
        return new DecimalFormat("0.00").format(d / Math.pow(1024.0d, iLog)) + cCharAt + "B";
    }

    public static String decryptByAES_CBC(byte[] bArr, byte[] bArr2, byte[] bArr3) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(bArr2, "AES");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(bArr3);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(2, secretKeySpec, ivParameterSpec);
            return new String(cipher.doFinal(bArr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] hexToByteArray(String str) {
        byte[] bArr;
        int length = str.length();
        if (length % 2 == 1) {
            length++;
            bArr = new byte[length / 2];
            str = "0" + str;
        } else {
            bArr = new byte[length / 2];
        }
        int i = 0;
        int i2 = 0;
        while (i < length) {
            int i3 = i + 2;
            bArr[i2] = (byte) Integer.parseInt(str.substring(i, i3), 16);
            i2++;
            i = i3;
        }
        return bArr;
    }

    public static String getFileSha1(File file) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] bArr = new byte[4096];
            while (true) {
                int i = fileInputStream.read(bArr);
                if (i == -1) {
                    fileInputStream.close();
                    return byteArrayToHex(messageDigest.digest());
                }
                messageDigest.update(bArr, 0, i);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void copyStream(InputStream inputStream, OutputStream outputStream) {
        try {
            byte[] bArr = new byte[4096];
            while (true) {
                int i = inputStream.read(bArr);
                if (i == -1) {
                    return;
                } else {
                    outputStream.write(bArr, 0, i);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String byteArrayToHex(byte[] bArr) {
        StringBuffer stringBuffer = new StringBuffer();
        for (byte b : bArr) {
            String upperCase = Integer.toHexString(b & 255).toUpperCase();
            if (upperCase.length() < 2) {
                stringBuffer.append(0);
            }
            stringBuffer.append(upperCase);
        }
        return stringBuffer.toString();
    }

    public static String convertBytesToString(long j) {
        if (j < 1024) {
            return j + " B";
        }
        double d = j;
        int iLog = (int) (Math.log(d) / Math.log(1024.0d));
        char cCharAt = "KMGTPE".charAt(iLog - 1);
        return new DecimalFormat("#.##").format(d / Math.pow(1024.0d, iLog)) + " " + cCharAt + "B";
    }

    public static String getDataMD5(byte[] bArr) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(bArr);
            return byteArrayToHex(messageDigest.digest());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getFileMD5(File file) {
        try {
            if (!file.isFile()) {
                return null;
            }
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] bArr = new byte[1024];
            while (true) {
                int i = fileInputStream.read(bArr, 0, 1024);
                if (i != -1) {
                    messageDigest.update(bArr, 0, i);
                } else {
                    fileInputStream.close();
                    return byteArrayToHex(messageDigest.digest());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getStrMD5(String str) {
        return getDataMD5(str.getBytes(StandardCharsets.UTF_8));
    }

    public static String getStrSha1(String str) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update(str.getBytes(StandardCharsets.UTF_8));
            return byteArrayToHex(messageDigest.digest());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] decryptByAES_ECB(byte[] bArr, String str) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(str.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(2, secretKeySpec);
            return cipher.doFinal(bArr);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] encryptByAES_ECB(byte[] bArr, String str) {
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(str.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(1, secretKeySpec);
            return cipher.doFinal(bArr);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String convertMillisecondsToTime(long j) {
        long j2 = j / 1000;
        return String.format("%02d:%02d.%03d", Long.valueOf(j2 / 60), Long.valueOf(j2 % 60), Long.valueOf(j % 1000));
    }

    public static String convertSecondToString(long j) {
        StringBuilder sb = new StringBuilder();
        long j2 = j / 86400;
        long j3 = (j % 86400) / 3600;
        long j4 = (j % 3600) / 60;
        long j5 = j % 60;
        if (j2 > 0) {
            sb.append(j2);
            sb.append("天");
        }
        if (j3 > 0) {
            sb.append(j3);
            sb.append("小时");
        }
        if (j4 > 0) {
            sb.append(j4);
            sb.append("分钟");
        }
        if (j5 > 0) {
            sb.append(j5);
            sb.append("秒");
        }
        return sb.toString();
    }

    public static byte[] gzipCompress(byte[] bArr) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            GZIPOutputStream gZIPOutputStream = new GZIPOutputStream(byteArrayOutputStream);
            gZIPOutputStream.write(bArr);
            gZIPOutputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return byteArrayOutputStream.toByteArray();
    }

    public static byte[] gzipUncompress(byte[] bArr) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            GZIPInputStream gZIPInputStream = new GZIPInputStream(new ByteArrayInputStream(bArr));
            byte[] bArr2 = new byte[4096];
            while (true) {
                int i = gZIPInputStream.read(bArr2);
                if (i == -1) {
                    break;
                }
                byteArrayOutputStream.write(bArr2, 0, i);
            }
            gZIPInputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return byteArrayOutputStream.toByteArray();
    }
}
