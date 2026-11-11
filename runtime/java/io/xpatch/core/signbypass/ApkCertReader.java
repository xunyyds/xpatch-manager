package io.xpatch.core.signbypass;

import android.os.Build;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkCertReader {

    private static final byte[] SIG_BLOCK_MAGIC = new byte[]{
            0x41, 0x50, 0x4B, 0x20, 0x53, 0x69, 0x67, 0x20,
            0x42, 0x6C, 0x6F, 0x63, 0x6B, 0x20, 0x34, 0x32
    };

    public static final int ID_V2 = 0x7109871a;
    public static final int ID_V3 = 0xf05368c0;
    public static final int ID_V31 = 0x1b93ad61;

    private static final int EOCD_MIN_SIZE = 22;
    private static final int EOCD_SIG = 0x06054b50;
    private static final int FOOTER_SIZE = 24;
    private static final long MAX_SIG_BLOCK_SIZE = 16L * 1024L * 1024L;

    private static volatile String lastError = "";

    private ApkCertReader() {
    }

    public static String lastError() {
        String err = lastError;
        return err == null ? "" : err;
    }

    public static List<byte[]> readCertificates(File apk) {
        return readCertificates(apk, Build.VERSION.SDK_INT);
    }

    public static List<byte[]> readCertificates(File apk, int sdkInt) {
        lastError = "";
        List<byte[]> certs = readSigningBlockCertificates(apk, sdkInt);
        if (!certs.isEmpty()) {
            return certs;
        }
        String blockError = lastError;
        lastError = "";
        List<byte[]> v1 = readV1Certificates(apk);
        if (!v1.isEmpty()) {
            lastError = "";
            return v1;
        }
        if (blockError.length() > 0) {
            lastError = lastError.length() == 0 ? blockError : blockError + "；" + lastError;
        }
        return new ArrayList<byte[]>();
    }

    public static List<byte[]> readSigningBlockCertificates(File apk) {
        return readSigningBlockCertificates(apk, Build.VERSION.SDK_INT);
    }

    public static List<byte[]> readSigningBlockCertificates(File apk, int sdkInt) {
        List<byte[]> result = new ArrayList<byte[]>();
        lastError = "";
        if (apk == null || !apk.isFile()) {
            lastError = "文件不存在或不可读";
            return result;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(apk, "r");
            long fileLength = raf.length();
            if (fileLength < 1024) {
                lastError = "文件过小（" + fileLength + " 字节），不可能是 APK";
                return result;
            }
            int tailLength = (int) Math.min(fileLength, 0xFFFF + EOCD_MIN_SIZE + 32L);
            byte[] tail = new byte[tailLength];
            raf.seek(fileLength - tailLength);
            raf.readFully(tail);
            int eocdInTail = findEocd(tail);
            if (eocdInTail < 0) {
                lastError = "找不到 ZIP EOCD 记录";
                return result;
            }
            long eocdPos = fileLength - tailLength + eocdInTail;
            long cdOffset = readUInt32LE(tail, eocdInTail + 16);
            if (cdOffset <= FOOTER_SIZE || cdOffset > eocdPos) {
                lastError = "EOCD 中央目录偏移异常：" + cdOffset;
                return result;
            }
            byte[] footer = new byte[FOOTER_SIZE];
            raf.seek(cdOffset - FOOTER_SIZE);
            raf.readFully(footer);
            if (!magicAt(footer, 8)) {
                lastError = "没有 APK 签名块（v1 或未签名）";
                return result;
            }
            long sizeInFooter = readUInt64LE(footer, 0);
            if (sizeInFooter <= 0 || sizeInFooter > MAX_SIG_BLOCK_SIZE || sizeInFooter > cdOffset) {
                lastError = "签名块长度异常：" + sizeInFooter;
                return result;
            }
            byte[] block = null;
            long[] candidateStarts = new long[]{
                    cdOffset - sizeInFooter - 8,
                    cdOffset - sizeInFooter - FOOTER_SIZE
            };
            for (int i = 0; i < candidateStarts.length && block == null; i++) {
                long start = candidateStarts[i];
                if (start < 0) {
                    continue;
                }
                long available = cdOffset - FOOTER_SIZE - start;
                if (available <= 8 || available > MAX_SIG_BLOCK_SIZE) {
                    continue;
                }
                byte[] candidate = new byte[(int) available];
                raf.seek(start);
                raf.readFully(candidate);
                if (readUInt64LE(candidate, 0) == sizeInFooter) {
                    block = candidate;
                }
            }
            if (block == null) {
                lastError = "签名块自校验失败（块首长度字段不匹配）";
                return result;
            }
            byte[] v3 = null;
            byte[] v31 = null;
            byte[] v2 = null;
            int pos = 8;
            int end = block.length;
            while (pos + 12 <= end) {
                long pairLength = readUInt64LE(block, pos);
                if (pairLength < 4 || pairLength > (long) (end - pos) - 8) {
                    break;
                }
                int id = readIntLE(block, pos + 8);
                int valueStart = pos + 12;
                int valueLength = (int) pairLength - 4;
                byte[] value = Arrays.copyOfRange(block, valueStart, valueStart + valueLength);
                if (id == ID_V3) {
                    v3 = value;
                } else if (id == ID_V31) {
                    v31 = value;
                } else if (id == ID_V2) {
                    v2 = value;
                }
                pos += 8 + (int) pairLength;
            }
            if (sdkInt >= 33) {
                List<byte[]> fromV31 = parseSigners(v31);
                if (!fromV31.isEmpty()) {
                    return fromV31;
                }
            }
            List<byte[]> fromV3 = parseSigners(v3);
            if (!fromV3.isEmpty()) {
                return fromV3;
            }
            List<byte[]> fromV2 = parseSigners(v2);
            if (!fromV2.isEmpty()) {
                return fromV2;
            }
            lastError = "签名块里没有 v2/v3 证书";
            return result;
        } catch (Throwable th) {
            lastError = "解析签名块异常：" + th;
            return result;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static List<byte[]> readV1Certificates(File apk) {
        List<byte[]> result = new ArrayList<byte[]>();
        if (apk == null || !apk.isFile()) {
            return result;
        }
        ZipFile zip = null;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toUpperCase(Locale.US);
                if (!name.startsWith("META-INF/")) {
                    continue;
                }
                if (!name.endsWith(".RSA") && !name.endsWith(".DSA") && !name.endsWith(".EC")) {
                    continue;
                }
                InputStream in = null;
                try {
                    in = zip.getInputStream(entry);
                    Collection<? extends Certificate> certs = factory.generateCertificates(in);
                    for (Certificate cert : certs) {
                        result.add(cert.getEncoded());
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable th) {
            lastError = "读取 v1 证书异常：" + th;
            return result;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private static List<byte[]> parseSigners(byte[] value) {
        List<byte[]> certs = new ArrayList<byte[]>();
        if (value == null || value.length < 4) {
            return certs;
        }
        int signersLength = (int) readUInt32LE(value, 0);
        int signersEnd = clamp(4 + (long) signersLength, value.length);
        int pos = 4;
        while (pos + 4 <= signersEnd) {
            int signerLength = (int) readUInt32LE(value, pos);
            int signerStart = pos + 4;
            int signerEnd = clamp(signerStart + (long) signerLength, signersEnd);
            parseSigner(value, signerStart, signerEnd, certs);
            if (signerEnd <= pos) {
                break;
            }
            pos = signerEnd;
        }
        return certs;
    }

    private static void parseSigner(byte[] data, int start, int end, List<byte[]> out) {
        int pos = start;
        if (pos + 4 > end) {
            return;
        }
        int signedDataLength = (int) readUInt32LE(data, pos);
        int signedDataStart = pos + 4;
        int signedDataEnd = clamp(signedDataStart + (long) signedDataLength, end);
        int cursor = signedDataStart;
        if (cursor + 4 > signedDataEnd) {
            return;
        }
        int digestsLength = (int) readUInt32LE(data, cursor);
        cursor = clamp(cursor + 4L + digestsLength, signedDataEnd);
        if (cursor + 4 > signedDataEnd) {
            return;
        }
        int certsLength = (int) readUInt32LE(data, cursor);
        int certsStart = cursor + 4;
        int certsEnd = clamp(certsStart + (long) certsLength, signedDataEnd);
        int p = certsStart;
        while (p + 4 <= certsEnd) {
            int certLength = (int) readUInt32LE(data, p);
            int certStart = p + 4;
            int certEnd = clamp(certStart + (long) certLength, certsEnd);
            if (certEnd <= certStart) {
                break;
            }
            out.add(Arrays.copyOfRange(data, certStart, certEnd));
            p = certEnd;
        }
    }

    private static int findEocd(byte[] tail) {
        int min = Math.max(0, tail.length - EOCD_MIN_SIZE - 0xFFFF);
        for (int i = tail.length - EOCD_MIN_SIZE; i >= min; i--) {
            if ((tail[i] & 0xFF) != 0x50 || (tail[i + 1] & 0xFF) != 0x4B
                    || (tail[i + 2] & 0xFF) != 0x05 || (tail[i + 3] & 0xFF) != 0x06) {
                continue;
            }
            int commentLength = (tail[i + 20] & 0xFF) | ((tail[i + 21] & 0xFF) << 8);
            if (i + EOCD_MIN_SIZE + commentLength == tail.length) {
                return i;
            }
        }
        return -1;
    }

    private static boolean magicAt(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + SIG_BLOCK_MAGIC.length > data.length) {
            return false;
        }
        for (int i = 0; i < SIG_BLOCK_MAGIC.length; i++) {
            if (data[offset + i] != SIG_BLOCK_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static int clamp(long value, int max) {
        if (value < 0) {
            return 0;
        }
        return (int) Math.min(value, (long) max);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readUInt32LE(byte[] data, int offset) {
        return readIntLE(data, offset) & 0xFFFFFFFFL;
    }

    private static long readUInt64LE(byte[] data, int offset) {
        long low = readUInt32LE(data, offset);
        long high = readUInt32LE(data, offset + 4);
        return (high << 32) | low;
    }
}
