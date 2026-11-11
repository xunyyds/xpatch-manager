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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lsposed.hidden.HiddenApiBypass;

public final class SignatureFacade {

    private static final String TAG = "XpSigFacade";

    private static final boolean HOOK_WRITE_TO_PARCEL = true;

    private static volatile boolean attempted;
    private static volatile boolean retried;
    private static volatile boolean primitiveReady;

    private static volatile Member toByteArrayMember;

    private static volatile boolean toByteArrayHooked;
    private static volatile boolean hashCodeHooked;
    private static volatile boolean equalsHooked;
    private static volatile boolean writeToParcelHooked;

    private static volatile Mapping mapping = Mapping.EMPTY;

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
            if (originalSet == null) {
                log("跳过签名原语层：原证书集合里有空项，整份不可信（fail-closed）");
                return false;
            }
            byte[][] fakeSet = readPatchedCertificates();
            if (fakeSet == null || fakeSet.length == 0) {
                log("跳过签名原语层：读不到当前 APK 证书（" + ApkCertReader.lastError() + "）");
                return false;
            }
            if (fakeSet.length > originalSet.length) {
                log("补丁证书 " + fakeSet.length + " 张多于原证书 " + originalSet.length
                        + " 张，无法配对，跳过（fail-closed）");
                return false;
            }
            Map<ByteKey, byte[]> pairs = buildPairs(fakeSet, originalSet);
            if (pairs == null) {
                return false;
            }
            if (pairs.isEmpty()) {
                log("签名原语层无需介入：当前 APK 证书与原证书一致");
                return false;
            }
            Mapping next = new Mapping(pairs);
            boolean ready = hookPrimitives();
            if (ready) {
                mapping = next;
            }
            primitiveReady = ready;
            log("签名原语层" + (ready ? "已安装" : "未就绪（四个原语 hook 未全部成功，映射不启用）")
                    + "：归一化 " + next.size() + " 张证书（"
                    + SigningDetailsData.sha256Prefix(next.firstFake) + " -> "
                    + SigningDetailsData.sha256Prefix(next.firstOriginal) + "）"
                    + "；toByteArray=" + state(toByteArrayHooked)
                    + ", hashCode=" + state(hashCodeHooked)
                    + ", equals=" + state(equalsHooked)
                    + ", writeToParcel=" + state(writeToParcelHooked));
            return primitiveReady;
        } catch (Throwable th) {
            log("签名原语层安装失败（fail-open，应用将看到重签证书）：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static String state(boolean ok) {
        return ok ? "ok" : "fail";
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
        Mapping current = mapping;
        return "原语层=" + (primitiveReady ? "开启" : "关闭")
                + "，映射证书=" + current.size()
                + (current.firstFake != null ? "，" + SigningDetailsData.sha256Prefix(current.firstFake) : "");
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
        return mapping.normalize(raw);
    }

    private static Map<ByteKey, byte[]> buildPairs(byte[][] fakeSet, byte[][] originalSet) {
        Map<ByteKey, byte[]> pairs = new LinkedHashMap<ByteKey, byte[]>();
        for (int i = 0; i < fakeSet.length; i++) {
            if (indexOf(originalSet, fakeSet[i]) >= 0) {
                continue;
            }
            if (i >= originalSet.length || originalSet[i] == null) {
                log("补丁证书比原证书多，无法配对，跳过（fail-closed）");
                return null;
            }
            ByteKey key = new ByteKey(fakeSet[i]);
            if (pairs.containsKey(key)) {
                log("补丁证书重复出现，配对存在歧义，跳过（fail-closed）");
                return null;
            }
            pairs.put(key, originalSet[i]);
        }
        return pairs;
    }

    private static int indexOf(byte[][] set, byte[] target) {
        for (int i = 0; i < set.length; i++) {
            if (set[i] != null && Arrays.equals(set[i], target)) {
                return i;
            }
        }
        return -1;
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
                return null;
            }
            byte[] raw = signature.toByteArray();
            if (raw == null || raw.length == 0) {
                return null;
            }
            result[i] = raw.clone();
        }
        return result;
    }

    private static boolean hookPrimitives() {
        if (!toByteArrayHooked) {
            try {
                Method method = Signature.class.getMethod("toByteArray", new Class[0]);
                toByteArrayMember = method;
                toByteArrayHooked = XpHook.doHook(method, 0, new ToByteArrayCallback()) != null;
            } catch (Throwable th) {
                log("hook Signature.toByteArray 失败：" + th);
            }
        }
        if (!hashCodeHooked) {
            try {
                Method method = Signature.class.getMethod("hashCode", new Class[0]);
                hashCodeHooked = XpHook.doHook(method, 0, new HashCodeCallback()) != null;
            } catch (Throwable th) {
                log("hook Signature.hashCode 失败：" + th);
            }
        }
        if (!equalsHooked) {
            try {
                Method method = Signature.class.getMethod("equals", new Class[]{Object.class});
                equalsHooked = XpHook.doHook(method, 0, new EqualsCallback()) != null;
            } catch (Throwable th) {
                log("hook Signature.equals 失败：" + th);
            }
        }
        if (HOOK_WRITE_TO_PARCEL && !writeToParcelHooked) {
            try {
                Method method = Signature.class.getMethod("writeToParcel", new Class[]{Parcel.class, Integer.TYPE});
                writeToParcelHooked = XpHook.doHook(method, 0, new WriteToParcelCallback()) != null;
            } catch (Throwable th) {
                log("hook Signature.writeToParcel 失败：" + th);
            }
        }
        return toByteArrayHooked && hashCodeHooked && equalsHooked
                && (!HOOK_WRITE_TO_PARCEL || writeToParcelHooked);
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

    private static final class ByteKey {
        private final byte[] bytes;
        private final int hash;

        private ByteKey(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ByteKey)) {
                return false;
            }
            ByteKey other = (ByteKey) obj;
            return hash == other.hash && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class Mapping {
        static final Mapping EMPTY = new Mapping(Collections.<ByteKey, byte[]>emptyMap());

        final Map<ByteKey, byte[]> pairs;
        final byte[] firstFake;
        final byte[] firstOriginal;

        private Mapping(Map<ByteKey, byte[]> pairs) {
            this.pairs = Collections.unmodifiableMap(pairs);
            byte[] fake = null;
            byte[] original = null;
            for (Map.Entry<ByteKey, byte[]> entry : pairs.entrySet()) {
                fake = entry.getKey().bytes;
                original = entry.getValue();
                break;
            }
            this.firstFake = fake;
            this.firstOriginal = original;
        }

        int size() {
            return pairs.size();
        }

        byte[] normalize(byte[] raw) {
            if (raw == null || raw.length == 0 || pairs.isEmpty()) {
                return null;
            }
            return pairs.get(new ByteKey(raw));
        }
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
            byte[] normalized = SignatureFacade.normalize(
                    SignatureFacade.bytesOf((Signature) param.this0));
            if (normalized == null) {
                return;
            }
            param.setResult(Integer.valueOf(Arrays.hashCode(normalized)));
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
