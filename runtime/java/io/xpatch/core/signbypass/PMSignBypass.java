package io.xpatch.core.signbypass;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Log;
import io.xpatch.core.XpLayout;
import io.xpatch.core.reflect.XClass;
import io.xpatch.core.reflect.XField;
import io.xpatch.core.reflect.XMethod;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.hidden.HiddenApiBypass;

public class PMSignBypass {

    private static final String TAG = "XpSignBypass";

    private static final String TAG_FILL = "XpSignFill";

    /**
     * hasSigningCertificate 的两个重载在 PackageManager 与 ApplicationPackageManager 上可能指向同一个
     * Member（子类未 override 时 getMethod 返回父类方法），用 Member 集合去重，保证一个 Member 只挂一个回调。
     */
    private static final Set<Method> hasSigningCertificateHooked = new HashSet<Method>();

    private static final Set<Method> archiveInfoHooked = new HashSet<Method>();

    private static volatile boolean earlyInstalled;

    private static volatile boolean packageInfoHookInstalled;

    private static volatile boolean archiveHooksInstalled;

    private static volatile boolean signingInfoFallbackLogged;

    /** XpInit 兼容开关：本字段已不参与决策（hasSigningCertificate 无条件安装），仅用于日志口径。 */
    private static volatile boolean hookHasSigningCertificateEnabled = true;

    private static final int CONFIG_CACHE_MAX = 64;

    private static final Signature[] NO_CONFIG_CERTS = new Signature[0];

    /** sourceDir + lastModified + length -> 该包 config 里的原证书；负缓存用 NO_CONFIG_CERTS。 */
    private static final Map<String, Signature[]> configCertCache = new ConcurrentHashMap<String, Signature[]>();

    /** getPackageArchiveInfo 可能一层重载转调另一层重载：只允许最外层做一次路径映射，避免自我抵消。 */
    private static final ThreadLocal<Boolean> archiveRedirecting = new ThreadLocal<Boolean>();

    /** 原证书 -> 公钥的缓存锚点（首证字节），避免每次 PackageInfo 反序列化都做 X.509 解析。 */
    private static volatile byte[] publicKeysAnchor;

    private static volatile Collection<PublicKey> publicKeysCached;

    public static void enableSignBypassByHook() throws Exception {
        if (packageInfoHookInstalled) {
            XpHook.log0(TAG, "PackageInfo 层签名伪装已安装，跳过重复安装");
            return;
        }
        try {
            Constructor<?> constructor = XClass.getInit(PackageInfo.class, Parcel.class);
            XpHook.XpUnhook token = XpHook.doHook(constructor, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam xpParam) throws Throwable {
                    xpParam.proceed();
                    PackageInfo packageInfo = (PackageInfo) xpParam.this0;
                    PMSignBypass.replaceSignature(packageInfo);
                    if (packageInfo == null || packageInfo.packageName == null
                            || !packageInfo.packageName.equals(AppEnv.packageName) || AppEnv.read_version_code <= 0) {
                        return;
                    }
                    packageInfo.setLongVersionCode(AppEnv.read_version_code);
                }
            });
            if (token == null) {
                // hook 引擎拒绝构造函数时不能静默成功（原实现把返回值丢掉了）
                XpHook.log0(TAG, "PackageInfo(Parcel) 构造 hook 安装失败，退回 PackageInfo.CREATOR 替换");
                enableSignBypass();
                return;
            }
            packageInfoHookInstalled = true;
            XpHook.log0(TAG, "PackageInfo(Parcel) 构造 hook 已安装");
        } catch (Exception e) {
            XpHook.log0(TAG, "签名伪装 hook 安装失败：" + Log.getStackTraceString(e));
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
                if (packageInfo != null && packageInfo.packageName != null
                        && packageInfo.packageName.equals(AppEnv.packageName) && AppEnv.read_version_code > 0) {
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
            XpHook.log0(TAG, "清理 Parcel.mCreators 失败：" + Log.getStackTraceString(th));
        }
        try {
            ((Map) XField.clz((Class<?>) Parcel.class).name("sPairedCreators").get()).clear();
        } catch (Throwable th2) {
            XpHook.log0(TAG, "清理 Parcel.sPairedCreators 失败：" + Log.getStackTraceString(th2));
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
        packageInfoHookInstalled = true;
        XpHook.log0(TAG, "PackageInfo.CREATOR / PackageParser.generatePackageInfo 兜底 hook 已安装");
    }

    /**
     * 兼容 W1 的旧调用点：开关不再参与决策（hasSigningCertificate 必须无条件安装，见 installEarly）。
     */
    public static void setHasSigningCertificateEnabled(boolean enabled) {
        hookHasSigningCertificateEnabled = enabled;
    }

    /**
     * 兼容 W1 的旧调用点。历史上这里起线程 sleep 1500ms 再装 hook，导致 Application.onCreate 里的
     * 自校验必然早于安装；现在同步立即安装，不再有线程与睡眠。
     */
    public static void installHasSigningCertificateLater() {
        if (!earlyInstalled) {
            XpHook.log0(TAG, "installHasSigningCertificateLater 已废弃：改为同步立即安装");
        }
        installEarly();
    }

    /**
     * 立即在 PackageManager / ApplicationPackageManager 上安装 hasSigningCertificate 的
     * (String, byte[], int) 与 (int, byte[], int) 两个重载。不依赖 appContext、不 sleep、幂等；
     * 失败时不置位，允许下一次调用（DeferredSignHooks.installAll）重试一次。
     */
    public static synchronized boolean installEarly() {
        if (earlyInstalled) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 28) {
            XpHook.log0(TAG, "hasSigningCertificate 需要 API 28+，当前 " + Build.VERSION.SDK_INT + "，跳过");
            return false;
        }
        boolean hooked = false;
        hooked |= hookHasSigningCertificate(PackageManager.class);
        Class<?> runtime = XClass.loadEx("android.app.ApplicationPackageManager");
        if (runtime != null) {
            hooked |= hookHasSigningCertificate(runtime);
        } else {
            XpHook.log0(TAG, "找不到 ApplicationPackageManager，hasSigningCertificate 只挂了 PackageManager");
        }
        earlyInstalled = hooked;
        XpHook.log0(TAG, hooked
                ? "hasSigningCertificate hook 已安装（默认档位即生效；String/uid 两种重载）"
                : "hasSigningCertificate hook 未安装（应用若用它自校验会看到补丁证书）");
        return hooked;
    }

    private static boolean hookHasSigningCertificate(Class<?> cls) {
        boolean hooked = false;
        hooked |= hookHasSigningCertificate(cls, new Class[]{String.class, byte[].class, Integer.TYPE});
        hooked |= hookHasSigningCertificate(cls, new Class[]{Integer.TYPE, byte[].class, Integer.TYPE});
        return hooked;
    }

    private static boolean hookHasSigningCertificate(Class<?> cls, Class<?>[] params) {
        Method method;
        try {
            method = cls.getMethod("hasSigningCertificate", params);
        } catch (Throwable th) {
            XpHook.log0(TAG, cls.getSimpleName() + ".hasSigningCertificate(" + describeParams(params) + ") 不存在：" + th);
            return false;
        }
        String label = cls.getName() + ".hasSigningCertificate(" + describeParams(params) + ")";
        synchronized (hasSigningCertificateHooked) {
            if (hasSigningCertificateHooked.contains(method)) {
                return true;
            }
            XpHook.XpUnhook token = XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam xpParam) throws Throwable {
                    if (PMSignBypass.isExpectedSigningCertificateQuery(xpParam.args)) {
                        xpParam.setResult(Boolean.TRUE);
                    } else {
                        xpParam.proceed();
                    }
                }
            });
            if (token == null) {
                XpHook.log0(TAG, "hook " + label + " 失败");
                return false;
            }
            hasSigningCertificateHooked.add(method);
        }
        XpHook.log0(TAG, "hasSigningCertificate hook 已安装：" + label);
        return true;
    }

    /**
     * 同时支持 (String packageName, byte[] cert, int type) 与 (int uid, byte[] cert, int type)；
     * uid 版按 Process.myUid() 认定"本包"。type: 0=CERT_INPUT_RAW_X509，1=CERT_INPUT_SHA256。
     */
    public static boolean isExpectedSigningCertificateQuery(Object[] objArr) {
        if (objArr == null || objArr.length < 3 || !(objArr[1] instanceof byte[])) {
            return false;
        }
        Object target = objArr[0];
        boolean self;
        if (target instanceof String) {
            self = AppEnv.packageName != null && AppEnv.packageName.equals(target);
        } else if (target instanceof Integer) {
            self = ((Integer) target).intValue() == Process.myUid();
        } else {
            self = false;
        }
        if (!self) {
            return false;
        }
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

    /**
     * 判定集 = 当前证书（signing_details.apk 优先，缺省退回 legacy sign）+ 历史证书链（v3 轮换场景）。
     */
    private static Signature[] signCheckCandidates() {
        SigningDetailsData data = SigningDetailsData.get();
        List<Signature> all = new ArrayList<Signature>();
        Signature[] current = (data != null && data.hasApk()) ? data.apk : AppEnv.signatures;
        if (current != null) {
            all.addAll(Arrays.asList(current));
        }
        if (data != null && data.hasHistory()) {
            for (Signature signature : data.history) {
                if (!containsSameBytes(all, signature)) {
                    all.add(signature);
                }
            }
        }
        return all.toArray(new Signature[all.size()]);
    }

    private static boolean containsSameBytes(List<Signature> list, Signature target) {
        if (target == null) {
            return true;
        }
        byte[] bytes = SignatureFacade.bytesOf(target);
        if (bytes == null) {
            return true;
        }
        for (Signature signature : list) {
            if (signature != null && Arrays.equals(bytes, SignatureFacade.bytesOf(signature))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSignatureRaw(byte[] bArr) {
        if (bArr == null) {
            return false;
        }
        for (Signature signature : signCheckCandidates()) {
            byte[] raw = signature == null ? null : SignatureFacade.bytesOf(signature);
            if (raw != null && Arrays.equals(bArr, raw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSignatureSha256(byte[] bArr) {
        if (bArr == null) {
            return false;
        }
        for (Signature signature : signCheckCandidates()) {
            byte[] raw = signature == null ? null : SignatureFacade.bytesOf(signature);
            if (raw == null) {
                continue;
            }
            byte[] digest = sha256(raw);
            if (digest != null && Arrays.equals(bArr, digest)) {
                return true;
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
                XpHook.log0(TAG, "patchPackageInfoReply 失败：" + Log.getStackTraceString(th));
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
        String packageName = packageInfo.packageName;
        if (packageName != null && packageName.equals(AppEnv.packageName)) {
            // 只对本包改写 appComponentFactory，且没有原厂工厂时保持 null（空串会让 LoadedApk 的
            // Class.forName("") 抛异常）；其它应用的元数据一律不动。
            if (packageInfo.applicationInfo != null) {
                String factory = AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
                if (factory != null && factory.length() == 0) {
                    factory = null;
                }
                packageInfo.applicationInfo.appComponentFactory = factory;
            }
            applySigningDetails(packageInfo);
            return;
        }
        checkAndFillTargetAppSign(packageInfo);
    }

    /**
     * signatures 与 signingInfo 必须同源（都用 signing_details.apk，缺省退回 legacy sign），
     * 否则同一个 PackageInfo 会给出两套互相矛盾的签名视图。
     */
    private static void applySigningDetails(PackageInfo packageInfo) {
        SigningDetailsData data = SigningDetailsData.get();
        Signature[] originals = (data != null && data.hasApk()) ? data.apk : AppEnv.signatures;
        if (originals == null || originals.length == 0) {
            return;
        }
        if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
            packageInfo.signatures = cloneSignatures(originals);
        }
        // PackageInfo.signingInfo 是 API 28 才有的字段：先判版本，避免 26/27 上每次反序列化都抛
        // NoSuchFieldError 并写一条完整堆栈
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        try {
            SigningInfo rebuilt = buildSigningInfo(packageInfo.signingInfo, data, originals);
            if (rebuilt != null) {
                packageInfo.signingInfo = rebuilt;
            }
        } catch (Throwable th) {
            logOnce("重建 signingInfo 失败，仅替换 signatures：" + th);
        }
    }

    private static SigningInfo buildSigningInfo(SigningInfo old, SigningDetailsData data, Signature[] originals) {
        if (old == null) {
            return null;
        }
        int scheme = schemeOf(old, data);
        Signature[] historySource = data != null && data.hasHistory() ? data.history : originals;
        Collection<Signature> apkSigners = new ArrayList<Signature>(Arrays.asList(cloneSignatures(originals)));
        Collection<Signature> history = new ArrayList<Signature>(Arrays.asList(cloneSignatures(historySource)));
        Collection<PublicKey> publicKeys = publicKeysOf(originals, old);
        if (Build.VERSION.SDK_INT >= 35) {
            // 公开构造：SigningInfo(int, Collection, Collection, Collection) 自 API 35 起进公开 SDK
            return new SigningInfo(scheme, apkSigners, publicKeys, history);
        }
        SigningInfo reflected = buildSigningInfoReflectively(scheme, apkSigners, publicKeys, history);
        if (reflected != null) {
            return reflected;
        }
        // 兜底（v0.1.6 的老办法）：getApkContentsSigners()/getSigningCertificateHistory() 返回的是
        // SigningInfo 内部数组，元素级替换即生效，保证 SigningInfo 与 signatures 同源
        if (patchSigningInfoInPlace(old, originals, historySource)) {
            logOnce("signingInfo 隐藏构造不可用，已改为元素级替换（API " + Build.VERSION.SDK_INT + "）");
            return null;
        }
        logOnce("signingInfo 无法重建（API " + Build.VERSION.SDK_INT + "），只替换 signatures");
        return null;
    }

    private static int schemeOf(SigningInfo old, SigningDetailsData data) {
        int scheme = 3;
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                scheme = old.getSchemeVersion();
            } catch (Throwable ignored) {
            }
        }
        if (data != null && data.scheme != SigningDetailsData.SCHEME_UNKNOWN) {
            scheme = data.scheme;
        }
        return scheme;
    }

    /**
     * API 28–34 上 SigningInfo(int, Collection, Collection, Collection) 是隐藏构造：
     * 先用 HiddenApiBypass（绕过 hidden API 限制）直接用 Unsafe 构造，再退回普通反射。
     */
    private static SigningInfo buildSigningInfoReflectively(int scheme, Collection<Signature> apkSigners,
            Collection<PublicKey> publicKeys, Collection<Signature> history) {
        try {
            Object instance = HiddenApiBypass.newInstance(SigningInfo.class,
                    Integer.valueOf(scheme), apkSigners, publicKeys, history);
            if (instance instanceof SigningInfo) {
                return (SigningInfo) instance;
            }
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> constructor = XClass.getInit(SigningInfo.class,
                    Integer.TYPE, Collection.class, Collection.class, Collection.class);
            Object instance = constructor.newInstance(Integer.valueOf(scheme), apkSigners, publicKeys, history);
            if (instance instanceof SigningInfo) {
                return (SigningInfo) instance;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean patchSigningInfoInPlace(SigningInfo info, Signature[] apkSigners, Signature[] history) {
        boolean patched = false;
        try {
            patched |= replaceArrayElements(info.getApkContentsSigners(), apkSigners);
        } catch (Throwable ignored) {
        }
        try {
            patched |= replaceArrayElements(info.getSigningCertificateHistory(), history);
        } catch (Throwable ignored) {
        }
        return patched;
    }

    private static boolean replaceArrayElements(Signature[] target, Signature[] source) {
        if (target == null || source == null || target.length == 0 || source.length == 0) {
            return false;
        }
        boolean changed = false;
        int count = Math.min(target.length, source.length);
        for (int i = 0; i < count; i++) {
            byte[] desired = source[i] == null ? null : source[i].toByteArray();
            if (desired == null) {
                continue;
            }
            // 必须用 bytesOf 读原始字节：target[i].toByteArray() 已被原语层归一化，读不到真实差异
            byte[] actual = SignatureFacade.bytesOf(target[i]);
            if (actual != null && Arrays.equals(desired, actual)) {
                continue;
            }
            target[i] = new Signature(desired);
            changed = true;
        }
        return changed;
    }

    private static Collection<PublicKey> publicKeysOf(Signature[] originals, SigningInfo old) {
        byte[] anchor = originals.length > 0 && originals[0] != null ? originals[0].toByteArray() : null;
        Collection<PublicKey> cached = publicKeysCached;
        if (anchor != null && cached != null && publicKeysAnchor != null && Arrays.equals(publicKeysAnchor, anchor)) {
            // X.509 解析比一次字节比较贵得多：同一份原证书只在首次解析
            return cached;
        }
        List<PublicKey> keys = new ArrayList<PublicKey>(originals.length);
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (Signature signature : originals) {
                Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(signature.toByteArray()));
                keys.add(certificate.getPublicKey());
            }
            if (keys.size() == originals.length) {
                rememberPublicKeys(anchor, keys);
                return keys;
            }
        } catch (Throwable th) {
            keys.clear();
        }
        // getPublicKeys() 自 API 35 起公开，低版本不碰它（否则每次 PackageInfo 都抛 NoSuchMethodError）
        if (Build.VERSION.SDK_INT >= 35 && old != null) {
            try {
                Collection<PublicKey> oldKeys = old.getPublicKeys();
                if (oldKeys != null) {
                    rememberPublicKeys(anchor, oldKeys);
                    return oldKeys;
                }
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<PublicKey>();
    }

    private static void rememberPublicKeys(byte[] anchor, Collection<PublicKey> keys) {
        if (anchor == null || keys == null || keys.isEmpty()) {
            return;
        }
        publicKeysAnchor = anchor;
        publicKeysCached = keys;
    }

    public static synchronized void installDeferredHooks() {
        if (archiveHooksInstalled) {
            return;
        }
        boolean hooked = false;
        Class<?> manager = XClass.loadEx("android.app.ApplicationPackageManager");
        if (manager != null) {
            hooked |= hookGetPackageArchiveInfo(manager);
        } else {
            XpHook.log0(TAG, "找不到 ApplicationPackageManager，getPackageArchiveInfo 路径重定向跳过");
        }
        Class<?> live = livePackageManagerClass();
        if (live != null && live != manager) {
            // OEM 包装子类：只有它自己 override 时才需要单独 hook（同 Member 会去重）
            hooked |= hookGetPackageArchiveInfo(live);
        }
        // 只有真的装上才置位，失败时留给下一次调用重试。
        // appContext 为空时 livePackageManagerClass() 拿不到 OEM 包装子类，这时即使
        // ApplicationPackageManager 已 hook 也不能标记完成：DeferredSignHooks.installAll()
        // 在 Application.onCreate 之前还有一次机会补装包装子类。
        archiveHooksInstalled = hooked && AppEnv.appContext != null;
        XpHook.log0(TAG, hooked
                ? "getPackageArchiveInfo 路径重定向已安装（本包读原包）"
                : "getPackageArchiveInfo 路径重定向未安装（自校验读 APK 时仍会看到补丁证书）");
    }

    private static Class<?> livePackageManagerClass() {
        try {
            Context context = AppEnv.appContext;
            if (context == null) {
                return null;
            }
            PackageManager manager = context.getPackageManager();
            return manager == null ? null : manager.getClass();
        } catch (Throwable th) {
            return null;
        }
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
        Method method;
        try {
            method = cls.getMethod("getPackageArchiveInfo", params);
        } catch (Throwable th) {
            XpHook.log0(TAG, "hook " + cls.getName() + ".getPackageArchiveInfo(" + describeParams(params) + ") 失败：" + th);
            return false;
        }
        String label = cls.getName() + ".getPackageArchiveInfo(" + describeParams(params) + ")";
        synchronized (archiveInfoHooked) {
            if (archiveInfoHooked.contains(method)) {
                return true;
            }
            if (XpHook.doHook(method, 0, new ArchiveInfoCallback()) == null) {
                XpHook.log0(TAG, "hook " + label + " 失败");
                return false;
            }
            archiveInfoHooked.add(method);
        }
        XpHook.log0(TAG, "getPackageArchiveInfo 重定向 hook 已安装：" + label);
        return true;
    }

    private static String describeParams(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }

    private static String redirectArchivePath(String apkPath) {
        if (apkPath == null || apkPath.length() == 0) {
            return null;
        }
        String origin = AppEnv.source_apk_path;
        String self = AppEnv.selfApk;
        if (origin == null || origin.length() == 0 || self == null || self.length() == 0) {
            return null;
        }
        if (self.equals(apkPath)) {
            return origin;
        }
        if (origin.equals(apkPath) || apkPath.contains("/cache/android_id/source-")) {
            return self;
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

    /** 重建包没有签名块时框架返回 null 签名：本包一律强制填原证书，避免"读自己 APK 发现未签名"。 */
    private static void forceFillOriginalSignatures(PackageInfo packageInfo) {
        if (packageInfo == null || (packageInfo.signatures != null && packageInfo.signatures.length > 0)) {
            return;
        }
        SigningDetailsData data = SigningDetailsData.get();
        Signature[] originals = (data != null && data.hasApk()) ? data.apk : AppEnv.signatures;
        if (originals == null || originals.length == 0) {
            return;
        }
        packageInfo.signatures = cloneSignatures(originals);
    }

    private static final class ArchiveInfoCallback implements XpHook.XpCallback {
        @Override
        public void onHook(XpHook.XpParam xpParam) throws Throwable {
            Object[] args = xpParam.args;
            // 两个重载可能是同一条调用链（int 版转调 flags 版），只允许最外层改写一次，
            // 否则 origin->self、self->origin 会互相抵消
            boolean outer = !Boolean.TRUE.equals(archiveRedirecting.get());
            String requestedPath = null;
            if (outer && args != null && args.length > 0 && args[0] instanceof String) {
                String redirected = redirectArchivePath((String) args[0]);
                if (redirected != null && !redirected.equals(args[0])) {
                    requestedPath = (String) args[0];
                    args[0] = redirected;
                }
            }
            if (outer) {
                archiveRedirecting.set(Boolean.TRUE);
            }
            try {
                xpParam.proceed();
            } finally {
                if (outer) {
                    archiveRedirecting.remove();
                }
            }
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
                forceFillOriginalSignatures(info);
            } catch (Throwable th) {
                XpHook.log0(TAG, "getPackageArchiveInfo 结果归一化失败：" + Log.getStackTraceString(th));
            }
            if (requestedPath != null) {
                XpHook.log0(TAG, "getPackageArchiveInfo 已按原包解析：" + requestedPath + " -> " + args[0]);
            }
        }
    }

    /**
     * PackageInfo(Parcel) 覆盖了所有反序列化路径（getPackageInfo / getInstalledPackages / 任意 binder 回复），
     * 对每个非本包都开一次 ZIP 会把 getInstalledPackages 变成 O(n) 文件 IO：按
     * sourceDir+lastModified+length 缓存解析结果（上限 64），非 XPatch 产物记负缓存，不再重复开包。
     */
    private static void checkAndFillTargetAppSign(PackageInfo packageInfo) {
        if (packageInfo.signatures == null || packageInfo.signatures.length == 0) {
            return;
        }
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (applicationInfo == null || applicationInfo.sourceDir == null || applicationInfo.sourceDir.length() == 0) {
            return;
        }
        FileStats stats = FileStats.of(applicationInfo.sourceDir);
        String key = applicationInfo.sourceDir + "|" + stats.lastModified + "|" + stats.length;
        Signature[] cached = configCertCache.get(key);
        if (cached == null) {
            cached = readConfigCertificates(applicationInfo.sourceDir);
            if (configCertCache.size() >= CONFIG_CACHE_MAX) {
                configCertCache.clear();
            }
            configCertCache.put(key, cached);
        }
        if (cached.length == 0) {
            return;
        }
        packageInfo.signatures = cloneSignatures(cached);
    }

    private static Signature[] readConfigCertificates(String sourceDir) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(sourceDir);
            // 目标包可能是路径混淆产物（不管是不是本管理器打的包），config 的条目名不可预测：
            // 由 XpLayout 按"候选正则 + 内容含 res_cfg"现场发现，找不到（普通 APK）返回 null。
            // 换包名不影响判定——布局与包名无关，只看条目名形状与内容。
            String json = XpLayout.readConfigText(zipFile);
            if (json == null) {
                return NO_CONFIG_CERTS;
            }
            JSONArray array = new JSONObject(json).optJSONArray("sign");
            if (array == null || array.length() == 0) {
                return NO_CONFIG_CERTS;
            }
            Signature[] signatures = new Signature[array.length()];
            for (int i = 0; i < array.length(); i++) {
                signatures[i] = new Signature(DataUtils.hexToByteArray(array.getString(i)));
            }
            return signatures;
        } catch (Throwable th) {
            XpHook.log0(TAG_FILL, "读取 " + sourceDir + " 的 config 失败：" + Log.getStackTraceString(th));
            return NO_CONFIG_CERTS;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class FileStats {
        final long lastModified;
        final long length;

        private FileStats(long lastModified, long length) {
            this.lastModified = lastModified;
            this.length = length;
        }

        static FileStats of(String path) {
            try {
                File file = new File(path);
                return new FileStats(file.lastModified(), file.length());
            } catch (Throwable th) {
                return new FileStats(0L, 0L);
            }
        }
    }

    private static void logOnce(String message) {
        if (signingInfoFallbackLogged) {
            return;
        }
        signingInfoFallbackLogged = true;
        XpHook.log0(TAG, message);
    }

    private static Signature[] cloneSignatures(Signature[] signatureArr) {
        if (signatureArr == null) {
            return null;
        }
        Signature[] signatureArr2 = new Signature[signatureArr.length];
        for (int i = 0; i < signatureArr.length; i++) {
            signatureArr2[i] = signatureArr[i] == null ? null : new Signature(signatureArr[i].toByteArray());
        }
        return signatureArr2;
    }
}
