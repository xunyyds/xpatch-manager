package io.xpatch.core.signbypass;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import io.xpatch.core.reflect.XClass;
import io.xpatch.core.reflect.XField;
import io.xpatch.core.reflect.XMethod;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

public class PMSignBypass {
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
            hookHasSigningCertificate();
        } catch (Exception e) {
            XpHook.log0("XpSignBypass", "signBypassByHooker fail: " + Log.getStackTraceString(e));
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
            XpHook.log0("XpSignBypass", "fail to clear Parcel.mCreators" + Log.getStackTraceString(th));
        }
        try {
            ((Map) XField.clz((Class<?>) Parcel.class).name("sPairedCreators").get()).clear();
        } catch (Throwable th2) {
            XpHook.log0("XpSignBypass", "fail to clear Parcel.sPairedCreators" + Log.getStackTraceString(th2));
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
        hookHasSigningCertificate();
    }

    private static void hookHasSigningCertificate() {
        hookHasSigningCertificate(PackageManager.class);
        try {
            hookHasSigningCertificate(XClass.loadEx("android.app.ApplicationPackageManager"));
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "skip ApplicationPackageManager.hasSigningCertificate hook: " + th);
        }
    }

    private static void hookHasSigningCertificate(Class<?> cls) {
        try {
            XpHook.doHook(XMethod.clz(cls).name("hasSigningCertificate").param(String.class, byte[].class, Integer.TYPE).ret(Boolean.TYPE).get(), 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam xpParam) throws Throwable {
                    if (PMSignBypass.isExpectedSigningCertificateQuery(xpParam.args)) {
                        xpParam.setResult(true);
                    } else {
                        xpParam.proceed();
                    }
                }
            });
        } catch (Throwable th) {
            XpHook.log0("XpSignBypass", "hook hasSigningCertificate fail in " + cls + ": " + Log.getStackTraceString(th));
        }
    }

    public static boolean isExpectedSigningCertificateQuery(Object[] objArr) {
        if (objArr != null && objArr.length >= 3 && AppEnv.packageName.equals(objArr[0]) && (objArr[1] instanceof byte[])) {
            Object obj = objArr[2];
            if ((obj instanceof Integer) && ((Integer) obj).intValue() == 1) {
                return hasSignatureSha256((byte[]) objArr[1]);
            }
            return false;
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
                XpHook.log0("XpSignBypass", "patchPackageInfoReply fail: " + Log.getStackTraceString(th));
            } finally {
                parcelObtain.recycle();
                parcelObtain2.recycle();
            }
        }
        return null;
    }

    public static void replaceSignature(PackageInfo packageInfo) {
        Signature[] apkContentsSigners;
        if (packageInfo.packageName.equals(AppEnv.packageName)) {
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                packageInfo.signatures = cloneSignatures(AppEnv.signatures);
            }
            if (packageInfo.signingInfo == null || (apkContentsSigners = packageInfo.signingInfo.getApkContentsSigners()) == null || apkContentsSigners.length <= 0) {
                return;
            }
            apkContentsSigners[0] = AppEnv.signatures[0];
            return;
        }
        checkAndFillTargetAppSign(packageInfo);
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
