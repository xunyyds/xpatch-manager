package io.xpatch.core.signbypass;

import android.content.pm.Signature;
import android.os.Parcel;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lsposed.hidden.HiddenApiBypass;

public final class SignatureFacade {

    private static final String TAG = "XpSigFacade";

    private static final boolean HOOK_WRITE_TO_PARCEL = true;

    private static volatile boolean attempted;
    private static volatile boolean retried;
    private static volatile boolean primitiveReady;

    private static volatile byte[][] fakeBytes = new byte[0][];
    private static volatile byte[][] originalBytes = new byte[0][];
    private static volatile int[] fakeHashes = new int[0];
    private static volatile int[] originalHashes = new int[0];

    private static volatile Member toByteArrayMember;
    private static volatile Member hashCodeMember;
    private static volatile Member equalsMember;

    private static final Object FIELD_LOCK = new Object();
    private static volatile Field signatureField;
    private static volatile boolean fieldResolved;

    private SignatureFacade() {
    }

    public static synchronized boolean install() {
        if (attempted) {
            return primitiveReady;
        }
        attempted = true;
        try {
            Signature[] originals = originals();
            if (originals == null || originals.length == 0) {
                log("跳过签名原语层：配置里没有原证书");
                return false;
            }
            byte[][] originalSet = toByteArrays(originals);
            byte[][] fakeSet = readPatchedCertificates();
            if (fakeSet == null || fakeSet.length == 0) {
                log("跳过签名原语层：读不到当前 APK 证书（" + ApkCertReader.lastError() + "）");
                return false;
            }
            int pairCount = Math.min(fakeSet.length, originalSet.length);
            if (fakeSet.length != originalSet.length) {
                log("补丁证书 " + fakeSet.length + " 张、原证书 " + originalSet.length
                        + " 张，按索引对齐前 " + pairCount + " 张");
            }
            List<byte[]> fakes = new ArrayList<byte[]>(pairCount);
            List<byte[]> origs = new ArrayList<byte[]>(pairCount);
            for (int i = 0; i < pairCount; i++) {
                if (Arrays.equals(fakeSet[i], originalSet[i])) {
                    continue;
                }
                fakes.add(fakeSet[i]);
                origs.add(originalSet[i]);
            }
            if (fakes.isEmpty()) {
                log("签名原语层无需介入：当前 APK 证书与原证书一致");
                return false;
            }
            applyMapping(fakes, origs);
            primitiveReady = hookPrimitives();
            log("签名原语层已安装：归一化 " + fakes.size() + " 张证书（"
                    + SigningDetailsData.sha256Prefix(fakeSet[0]) + " -> "
                    + SigningDetailsData.sha256Prefix(originalSet[0]) + "）"
                    + (primitiveReady ? "" : "，但全部 hook 均未装上"));
            return primitiveReady;
        } catch (Throwable th) {
            log("签名原语层安装失败（fail-open，应用将看到重签证书）：" + Log.getStackTraceString(th));
            return false;
        }
    }

    public static synchronized boolean retryIfNeeded() {
        if (primitiveReady || retried) {
            return primitiveReady;
        }
        retried = true;
        attempted = false;
        log("签名原语层延后重试一次");
        return install();
    }

    public static Signature[] originals() {
        SigningDetailsData data = SigningDetailsData.get();
        if (data != null && data.hasApk()) {
            return data.apk;
        }
        return AppEnv.signatures;
    }

    public static boolean isPrimitiveReady() {
        return primitiveReady;
    }

    public static String describe() {
        byte[][] fakes = fakeBytes;
        return "原语层=" + (primitiveReady ? "开启" : "关闭")
                + "，映射证书=" + fakes.length
                + (fakes.length > 0 ? "，" + SigningDetailsData.sha256Prefix(fakes[0]) : "");
    }

    public static byte[] bytesOf(Signature signature) {
        if (signature == null) {
            return null;
        }
        Field field = signatureField();
        if (field != null) {
            try {
                Object value = field.get(signature);
                if (value instanceof byte[]) {
                    return (byte[]) value;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String hex = signature.toCharsString();
            if (hex != null && hex.length() > 0) {
                return DataUtils.hexToByteArray(hex);
            }
        } catch (Throwable ignored) {
        }
        Member member = toByteArrayMember;
        if (member != null) {
            try {
                Object value = XpHook.invoke0(member, signature);
                if (value instanceof byte[]) {
                    return (byte[]) value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static byte[] normalize(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        byte[][] fakes = fakeBytes;
        byte[][] origs = originalBytes;
        for (int i = 0; i < fakes.length; i++) {
            if (fakes[i].length == raw.length && Arrays.equals(fakes[i], raw)) {
                return origs[i];
            }
        }
        return null;
    }

    static int indexOfFakeHash(int hash) {
        int[] hashes = fakeHashes;
        for (int i = 0; i < hashes.length; i++) {
            if (hashes[i] == hash) {
                return i;
            }
        }
        return -1;
    }

    private static void applyMapping(List<byte[]> fakes, List<byte[]> origs) {
        int size = fakes.size();
        byte[][] fakeArray = new byte[size][];
        byte[][] origArray = new byte[size][];
        int[] fakeHashArray = new int[size];
        int[] origHashArray = new int[size];
        for (int i = 0; i < size; i++) {
            fakeArray[i] = fakes.get(i);
            origArray[i] = origs.get(i);
            fakeHashArray[i] = Arrays.hashCode(fakeArray[i]);
            origHashArray[i] = Arrays.hashCode(origArray[i]);
        }
        fakeBytes = fakeArray;
        originalBytes = origArray;
        fakeHashes = fakeHashArray;
        originalHashes = origHashArray;
    }

    private static byte[][] readPatchedCertificates() {
        String path = AppEnv.selfApk;
        if (path == null || path.length() == 0) {
            return null;
        }
        List<byte[]> certs = ApkCertReader.readCertificates(new File(path));
        if (certs.isEmpty()) {
            return null;
        }
        return certs.toArray(new byte[certs.size()][]);
    }

    private static byte[][] toByteArrays(Signature[] signatures) {
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) {
            Signature signature = signatures[i];
            if (signature == null) {
                result[i] = new byte[0];
            } else {
                byte[] raw = signature.toByteArray();
                result[i] = raw == null ? new byte[0] : raw.clone();
            }
        }
        return result;
    }

    private static boolean hookPrimitives() {
        boolean hooked = false;
        Class<Signature> type = Signature.class;
        try {
            Method method = type.getMethod("toByteArray", new Class[0]);
            toByteArrayMember = method;
            hooked |= XpHook.doHook(method, 0, new ToByteArrayCallback()) != null;
        } catch (Throwable th) {
            log("hook Signature.toByteArray 失败：" + th);
        }
        try {
            Method method = type.getMethod("hashCode", new Class[0]);
            hashCodeMember = method;
            hooked |= XpHook.doHook(method, 0, new HashCodeCallback()) != null;
        } catch (Throwable th) {
            log("hook Signature.hashCode 失败：" + th);
        }
        try {
            Method method = type.getMethod("equals", new Class[]{Object.class});
            equalsMember = method;
            hooked |= XpHook.doHook(method, 0, new EqualsCallback()) != null;
        } catch (Throwable th) {
            log("hook Signature.equals 失败：" + th);
        }
        if (HOOK_WRITE_TO_PARCEL) {
            try {
                Method method = type.getMethod("writeToParcel", new Class[]{Parcel.class, Integer.TYPE});
                hooked |= XpHook.doHook(method, 0, new WriteToParcelCallback()) != null;
            } catch (Throwable th) {
                log("hook Signature.writeToParcel 失败：" + th);
            }
        }
        return hooked;
    }

    private static Field signatureField() {
        if (fieldResolved) {
            return signatureField;
        }
        synchronized (FIELD_LOCK) {
            if (fieldResolved) {
                return signatureField;
            }
            try {
                for (Field field : HiddenApiBypass.getInstanceFields(Signature.class)) {
                    if ("mSignature".equals(field.getName()) && field.getType() == byte[].class) {
                        field.setAccessible(true);
                        signatureField = field;
                        break;
                    }
                }
            } catch (Throwable th) {
                log("读取 Signature.mSignature 字段失败，改用 toCharsString 兜底：" + th);
            }
            fieldResolved = true;
            return signatureField;
        }
    }

    private static void log(String message) {
        XpHook.log0(TAG, message);
    }

    private static final class ToByteArrayCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam param) throws Throwable {
            param.proceed();
            Object result = param.getResult();
            if (!(result instanceof byte[])) {
                return;
            }
            byte[] normalized = SignatureFacade.normalize((byte[]) result);
            if (normalized != null) {
                param.setResult(normalized.clone());
            }
        }
    }

    private static final class HashCodeCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam param) throws Throwable {
            param.proceed();
            Object result = param.getResult();
            if (!(result instanceof Integer)) {
                return;
            }
            int index = SignatureFacade.indexOfFakeHash(((Integer) result).intValue());
            if (index < 0) {
                return;
            }
            byte[] raw = SignatureFacade.bytesOf((Signature) param.this0);
            if (raw == null || !Arrays.equals(raw, SignatureFacade.fakeBytes[index])) {
                return;
            }
            param.setResult(Integer.valueOf(SignatureFacade.originalHashes[index]));
        }
    }

    private static final class EqualsCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam param) throws Throwable {
            param.proceed();
            Object result = param.getResult();
            if (result instanceof Boolean && ((Boolean) result).booleanValue()) {
                return;
            }
            Object[] args = param.args;
            if (args == null || args.length < 1 || !(args[0] instanceof Signature)) {
                return;
            }
            byte[] self = SignatureFacade.bytesOf((Signature) param.this0);
            byte[] other = SignatureFacade.bytesOf((Signature) args[0]);
            if (self == null || other == null) {
                return;
            }
            byte[] selfNormalized = SignatureFacade.normalize(self);
            byte[] otherNormalized = SignatureFacade.normalize(other);
            if (selfNormalized == null && otherNormalized == null) {
                return;
            }
            byte[] left = selfNormalized != null ? selfNormalized : self;
            byte[] right = otherNormalized != null ? otherNormalized : other;
            param.setResult(Boolean.valueOf(Arrays.equals(left, right)));
        }
    }

    private static final class WriteToParcelCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam param) throws Throwable {
            Object[] args = param.args;
            if (args == null || args.length < 2 || !(args[0] instanceof Parcel)) {
                param.proceed();
                return;
            }
            byte[] raw = SignatureFacade.bytesOf((Signature) param.this0);
            byte[] normalized = SignatureFacade.normalize(raw);
            if (normalized == null) {
                param.proceed();
                return;
            }
            ((Parcel) args[0]).writeByteArray(normalized);
            param.setResult(null);
        }
    }
}
