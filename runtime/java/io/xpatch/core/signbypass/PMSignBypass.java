package io.xpatch.core.signbypass;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import io.xpatch.core.reflect.XClass;
import io.xpatch.core.reflect.XField;
import io.xpatch.core.reflect.XMethod;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class PMSignBypass {

    private static volatile boolean hookHasSigningCertificateEnabled = false;

    private static volatile boolean archiveHooksInstalled;

    public static void enableSignBypassByHook() throws Exception {
        try {
            XpHook.doHook(XClass.getInit(PackageInfo.class, Parcel.class), 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam xpParam) throws Throwable {
                    xpParam.proceed();
                    PackageInfo packageInfo = (PackageInfo) xpParam.this0;
                    PMSignBypass.replaceSignature(packageInfo);
                    if (!packageInfo.packageName.equals(AppEnv.packageName) || AppEnv.read_version_code <= 0) {
                        return;
                    }
                    packageInfo.setLongVersionCode(AppEnv.read_version_code);
                }
            });
            XpHook.log0("XpSignBypass", "PackageInfo(Parcel) 构造 hook 已安装");
            XpHook.log0("XpSignBypass", hookHasSigningCertificateEnabled
                    ? "hasSigningCertificate hook 延后到应用启动阶段安装"
                    : "已跳过 hasSigningCertificate hook（启动早期安全考虑）");
        } catch (Exception e) {
            XpHook.log0("XpSignBypass", "签名伪装 hook 安装失败：" + Log.getStackTraceString(e));
            enableSignBypass();
        }
    }

    public static void enableSignBypass() throws Exception {
        final Parcelable.Creator creator = PackageInfo.CREATOR;
        XField.clz((Class<?>) PackageInfo.class).name("CREATOR").set(new Parcelable.Creator<PackageInfo>() {

            @Override
            public PackageInfo createFromParcel(Parcel parcel) {
                PackageInfo packageInfo = (PackageInfo) creator.createFromParcel(parcel);
                PMSignBypass.replaceSignature(packageInfo);
                if (packageInfo.packageName.equals(AppEnv.packageName) && AppEnv.read_version_code > 0) {
                    packageInfo.setLongVersionCode(AppEnv.read_version_code);
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int i) {
                return (PackageInfo[]) creator.newArray(i);
            }
        });
        try {
            XMethod.obj(XField.clz((Class<?>) PackageManager.class).name("sPackageInfoCache").get()).name("clear").invoke(new Object[0]);
        } catch (Throwable unused) {
        }
        try {
            ((Map) XField.clz((Class<?>) Parcel.class).name("mCreators").get()).clear();
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "清理 Parcel.mCreators 失败：" + Log.getStackTraceString(th));
        }
        try {
            ((Map) XField.clz((Class<?>) Parcel.class).name("sPairedCreators").get()).clear();
        } catch (Throwable th2) {
            XpHook.log0("XpSignBypass", "清理 Parcel.sPairedCreators 失败：" + Log.getStackTraceString(th2));
        }
        XpHook.doHook(XMethod.clz("android.content.pm.PackageParser").ignoreParam().name("generatePackageInfo").get(), 0, new XpHook.XpCallback() {
            @Override
            public void onHook(XpHook.XpParam xpParam) throws Throwable {
                xpParam.proceed();
                PackageInfo packageInfo = (PackageInfo) xpParam.getResult();
                if (packageInfo == null) {
                    return;
                }
                PMSignBypass.replaceSignature(packageInfo);
            }
        });
        XpHook.log0("XpSignBypass", "hasSigningCertificate hook 交由启动后安装");
    }

    public static void setHasSigningCertificateEnabled(boolean enabled) {
        hookHasSigningCertificateEnabled = enabled;
    }





    public static void installHasSigningCertificateLater() {
        if (!hookHasSigningCertificateEnabled) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500L);
                    if (AppEnv.appContext == null) {
                        XpHook.log0("XpSignBypass", "hasSigningCertificate hook 跳过：appContext 为空");
                        return;
                    }
                    hookHasSigningCertificate(AppEnv.appContext.getPackageManager().getClass());
                } catch (Throwable th) {
                    XpHook.log0("XpSignBypass", "hasSigningCertificate hook 安装失败：" + Log.getStackTraceString(th));
                }
            }
        }).start();
    }

    public static synchronized void installDeferredHooks() {
        if (archiveHooksInstalled) {
            return;
        }
        archiveHooksInstalled = true;
        boolean hooked = false;
        hooked |= hookGetPackageArchiveInfo(PackageManager.class);
        Class<?> manager = XClass.loadEx("android.app.ApplicationPackageManager");
        if (manager != null) {
            hooked |= hookGetPackageArchiveInfo(manager);
        } else {
            XpHook.log0("XpSignBypass", "找不到 ApplicationPackageManager，getPackageArchiveInfo 路径重定向跳过");
        }
        XpHook.log0("XpSignBypass", hooked
                ? "getPackageArchiveInfo 路径重定向已安装（本包读原包）"
                : "getPackageArchiveInfo 路径重定向未安装（自校验读 APK 时仍会看到补丁证书）");

    }

    private static boolean hookGetPackageArchiveInfo(Class<?> cls) {
        boolean hooked = false;
        hooked |= hookArchiveInfo(cls, new Class[]{String.class, Integer.TYPE});
        if (Build.VERSION.SDK_INT >= 33) {
            Class<?> flagsClass = XClass.loadEx("android.content.pm.PackageManager$PackageInfoFlags");
            if (flagsClass != null) {
                hooked |= hookArchiveInfo(cls, new Class[]{String.class, flagsClass});
            }
        }
        return hooked;
    }

    private static boolean hookArchiveInfo(Class<?> cls, Class<?>[] params) {
        try {
            Method method = cls.getMethod("getPackageArchiveInfo", params);
            return XpHook.doHook(method, 0, new ArchiveInfoCallback()) != null;
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "hook " + cls.getName() + ".getPackageArchiveInfo 失败：" + th);
            return false;
        }
    }

    private static String redirectArchivePath(String apkPath) {
        if (apkPath == null || apkPath.length() == 0) {
            return null;
        }
        String origin = AppEnv.source_apk_path;
        if (origin == null || origin.length() == 0) {
            return null;
        }
        if (origin.equals(apkPath)) {
            return null;
        }
        if (apkPath.equals(AppEnv.selfApk)) {
            return origin;
        }
        try {
            String codePath = AppEnv.appContext == null ? null : AppEnv.appContext.getPackageCodePath();
            if (codePath != null && codePath.equals(apkPath)) {
                return origin;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class ArchiveInfoCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam xpParam) throws Throwable {
            Object[] args = xpParam.args;
            String originPath = null;
            if (args != null && args.length > 0 && args[0] instanceof String) {
                String redirected = redirectArchivePath((String) args[0]);
                if (redirected != null) {
                    originPath = (String) args[0];
                    args[0] = redirected;
                }
            }
            xpParam.proceed();
            Object result = xpParam.getResult();
            if (!(result instanceof PackageInfo)) {
                return;
            }
            PackageInfo info = (PackageInfo) result;
            if (info.packageName == null || !info.packageName.equals(AppEnv.packageName)) {
                return;
            }
            try {
                replaceSignature(info);
            } catch (Throwable th) {
                XpHook.log0("XpSignBypass", "getPackageArchiveInfo 结果归一化失败：" + Log.getStackTraceString(th));
            }
            if (originPath != null) {
                XpHook.log0("XpSignBypass", "getPackageArchiveInfo 已按原包解析：" + originPath + " -> " + args[0]);
            }
        }
    }

    private static void hookHasSigningCertificate(Class<?> cls) {
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod("hasSigningCertificate",
                    String.class, byte[].class, Integer.TYPE);
            XpHook.XpUnhook token = XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam xpParam) throws Throwable {
                    if (PMSignBypass.isExpectedSigningCertificateQuery(xpParam.args)) {
                        xpParam.setResult(true);
                    } else {
                        xpParam.proceed();
                    }
                }
            });
            XpHook.log0("XpSignBypass", "hasSigningCertificate hook 已安装：" + cls.getName()
                    + "，token=" + (token == null ? "null" : "ok"));
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "hasSigningCertificate hook 失败：" + Log.getStackTraceString(th));
        }
    }

    public static boolean isExpectedSigningCertificateQuery(Object[] objArr) {
        if (objArr != null && objArr.length >= 3 && AppEnv.packageName.equals(objArr[0]) && (objArr[1] instanceof byte[])) {
            Object obj = objArr[2];
            if (!(obj instanceof Integer)) {
                return false;
            }
            int type = ((Integer) obj).intValue();
            if (type == 1) {
                return hasSignatureSha256((byte[]) objArr[1]);
            }
            if (type == 0) {
                return hasSignatureRaw((byte[]) objArr[1]);
            }
            return false;
        }
        return false;
    }

    private static boolean hasSignatureRaw(byte[] bArr) {
        if (bArr == null || AppEnv.signatures == null) {
            return false;
        }
        for (Signature signature : AppEnv.signatures) {
            if (Arrays.equals(bArr, signature.toByteArray())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSignatureSha256(byte[] bArr) {
        if (bArr != null && AppEnv.signatures != null) {
            for (Signature signature : AppEnv.signatures) {
                if (Arrays.equals(bArr, sha256(signature.toByteArray()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] sha256(byte[] bArr) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bArr);
        } catch (Exception unused) {
            return null;
        }
    }

    public static byte[] patchPackageInfoReply(byte[] bArr) {
        PackageInfo packageInfo;
        if (bArr == null || bArr.length < 8) {
            return null;
        }
        Parcel parcelObtain = Parcel.obtain();
        Parcel parcelObtain2 = Parcel.obtain();
        try {
            parcelObtain.unmarshall(bArr, 0, bArr.length);
            parcelObtain.setDataPosition(0);
            if (parcelObtain.readInt() == 0 && parcelObtain.readInt() != 0 && (packageInfo = (PackageInfo) PackageInfo.CREATOR.createFromParcel(parcelObtain)) != null && AppEnv.packageName.equals(packageInfo.packageName)) {
                replaceSignature(packageInfo);
                if (AppEnv.read_version_code > 0) {
                    packageInfo.setLongVersionCode(AppEnv.read_version_code);
                }
                parcelObtain2.writeInt(0);
                parcelObtain2.writeInt(1);
                packageInfo.writeToParcel(parcelObtain2, 1);
                byte[] bArrMarshall = parcelObtain2.marshall();
                parcelObtain.recycle();
                parcelObtain2.recycle();
                return bArrMarshall;
            }
        } catch (Throwable th) {
            try {
                XpHook.log0("XpSignBypass", "patchPackageInfoReply 失败：" + Log.getStackTraceString(th));
            } finally {
                parcelObtain.recycle();
                parcelObtain2.recycle();
            }
        }
        return null;
    }

    public static void replaceSignature(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return;
        }
        if (packageInfo.applicationInfo != null) {
            packageInfo.applicationInfo.appComponentFactory =
                    AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
        }
        String packageName = packageInfo.packageName;
        if (packageName != null && packageName.equals(AppEnv.packageName)) {
            applySigningDetails(packageInfo);
            return;
        }
        checkAndFillTargetAppSign(packageInfo);
    }

    private static void applySigningDetails(PackageInfo packageInfo) {
        SigningDetailsData data = SigningDetailsData.get();
        Signature[] legacy = AppEnv.signatures;
        Signature[] current = (data != null && data.hasApk()) ? data.apk : SignatureFacade.originals();
        if ((legacy == null || legacy.length == 0) && (current == null || current.length == 0)) {
            return;
        }
        if (packageInfo.signatures != null && packageInfo.signatures.length > 0
                && legacy != null && legacy.length > 0) {
            packageInfo.signatures = cloneSignatures(legacy);
        }
        try {
            SigningInfo rebuilt = buildSigningInfo(packageInfo.signingInfo, data, current);
            if (rebuilt != null) {
                packageInfo.signingInfo = rebuilt;
            }
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "重建 signingInfo 失败，仅替换 signatures：" + Log.getStackTraceString(th));
        }
    }

    private static SigningInfo buildSigningInfo(SigningInfo old, SigningDetailsData data, Signature[] originals) {
        if (old == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT < 35) {
            return null;
        }
        int scheme = 3;
        try {
            scheme = old.getSchemeVersion();
        } catch (Throwable ignored) {
        }
        if (data != null && data.scheme != SigningDetailsData.SCHEME_UNKNOWN) {
            scheme = data.scheme;
        }
        Signature[] historySource = data != null && data.hasHistory() ? data.history : originals;
        Collection<Signature> apkSigners = new ArrayList<Signature>(Arrays.asList(cloneSignatures(originals)));
        Collection<Signature> history = new ArrayList<Signature>(Arrays.asList(cloneSignatures(historySource)));
        Collection<PublicKey> publicKeys = publicKeysOf(originals, old);
        return new SigningInfo(scheme, apkSigners, publicKeys, history);
    }

    private static Collection<PublicKey> publicKeysOf(Signature[] originals, SigningInfo old) {
        List<PublicKey> keys = new ArrayList<PublicKey>(originals.length);
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (Signature signature : originals) {
                Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(signature.toByteArray()));
                keys.add(certificate.getPublicKey());
            }
            if (keys.size() == originals.length) {
                return keys;
            }
        } catch (Throwable th) {
            keys.clear();
        }
        if (old != null) {
            try {
                Collection<PublicKey> oldKeys = old.getPublicKeys();
                if (oldKeys != null) {
                    return oldKeys;
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<PublicKey>();
    }

    private static void checkAndFillTargetAppSign(PackageInfo packageInfo) {
        if (packageInfo.signatures != null) {
            try {
                ZipFile zipFile = new ZipFile(packageInfo.applicationInfo.sourceDir);
                ZipEntry entry = zipFile.getEntry("xp/config.json");
                if (entry != null) {
                    JSONArray jSONArray = new JSONArray(new JSONObject(new String(DataUtils.readAllBytes(zipFile.getInputStream(entry)))).getString("sign"));
                    Signature[] signatureArr = new Signature[jSONArray.length()];
                    for (int i = 0; i < jSONArray.length(); i++) {
                        signatureArr[i] = new Signature(DataUtils.hexToByteArray(jSONArray.getString(i)));
                    }
                    packageInfo.signatures = cloneSignatures(signatureArr);
                }
                zipFile.close();
            } catch (Exception e) {
                XpHook.log0("XpSignFill", Log.getStackTraceString(e));
            }
        }
    }

    private static Signature[] cloneSignatures(Signature[] signatureArr) {
        if (signatureArr == null) {
            return null;
        }
        Signature[] signatureArr2 = new Signature[signatureArr.length];
        for (int i = 0; i < signatureArr.length; i++) {
            signatureArr2[i] = new Signature(signatureArr[i].toByteArray());
        }
        return signatureArr2;
    }
}
