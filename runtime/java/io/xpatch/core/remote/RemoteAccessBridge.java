package io.xpatch.core.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import io.xpatch.remote.IRemoteAccessService;
import java.io.FileNotFoundException;

public final class RemoteAccessBridge {
    private static final String AUTHORITY = "io.xpatch.FrameworkBridge";
    private static final long FALLBACK_RETRY_INTERVAL_MS = 3000;
    private static final String KEY_APP_PACKAGE_NAME = "appPackageName";
    private static final String KEY_BINDER = "binder";
    static final String KEY_PREF_BUNDLE = "bundle";
    static final String KEY_PREF_DIFF_CLEAR = "clear";
    static final String KEY_PREF_DIFF_DELETE = "delete";
    static final String KEY_PREF_DIFF_PUT = "put";
    static final String KEY_PREF_MAP = "map";
    private static final String METHOD_GET_REMOTE_ACCESS_BINDER = "getRemoteAccessBinder";
    private static final String TAG = "XpRemoteAccess";
    private static volatile long lastResolveTime;
    private static final Uri CONTENT_URI = Uri.parse("content://io.xpatch.FrameworkBridge");
    private static volatile IRemoteAccessService remoteService = NoOpRemoteAccessService.INSTANCE;
    private static volatile boolean lastResolvedFallback = true;

    private RemoteAccessBridge() {
    }

    










    public static void initialize(Context context) {
        if (context != null) {
            resolveRemoteService(context, true);
        }
    }

    private static boolean isProviderInstalled(Context context) {
        try {
            return context.getPackageManager().resolveContentProvider(AUTHORITY, 0) != null;
        } catch (Throwable th) {
            return false;
        }
    }

    public static SharedPreferences getRemotePreferences(Context context, String str, String str2) {
        return new RemotePreferences(context != null ? context.getApplicationContext() : null, str, str2);
    }

    public static String[] listRemoteFiles(Context context, String str) {
        try {
            String[] strArrListRemoteFiles = requireService(context).listRemoteFiles(str);
            return strArrListRemoteFiles != null ? strArrListRemoteFiles : new String[0];
        } catch (RemoteException e) {
            onRemoteFailure(e);
            return new String[0];
        }
    }

    public static ParcelFileDescriptor openRemoteFile(Context context, String str, String str2) throws FileNotFoundException {
        try {
            return requireService(context).openRemoteFile(str, str2);
        } catch (RemoteException e) {
            onRemoteFailure(e);
            throw new FileNotFoundException(String.valueOf(e.getMessage()));
        }
    }

    public static boolean deleteRemoteFile(Context context, String str, String str2) {
        try {
            return requireService(context).deleteRemoteFile(str, str2);
        } catch (RemoteException e) {
            onRemoteFailure(e);
            return false;
        }
    }

    static Bundle requestRemotePreferences(Context context, String str, String str2) {
        try {
            return requireService(context).requestRemotePreferences(str, str2);
        } catch (RemoteException e) {
            onRemoteFailure(e);
            return new Bundle();
        }
    }

    static boolean updateRemotePreferences(Context context, String str, String str2, Bundle bundle) {
        try {
            requireService(context).updateRemotePreferences(str, str2, bundle);
            return true;
        } catch (RemoteException e) {
            onRemoteFailure(e);
            return false;
        }
    }

    private static IRemoteAccessService requireService(Context context) {
        resolveRemoteService(context != null ? context.getApplicationContext() : null, false);
        return remoteService;
    }

    private static boolean shouldRetryResolve() {
        return lastResolvedFallback && SystemClock.uptimeMillis() - lastResolveTime >= FALLBACK_RETRY_INTERVAL_MS;
    }

    private static synchronized void resolveRemoteService(Context context, boolean z) {
        IRemoteAccessService iRemoteAccessService = remoteService;
        if (!z && iRemoteAccessService != null && iRemoteAccessService.asBinder().pingBinder() && !shouldRetryResolve()) {
            return;
        }
        lastResolveTime = SystemClock.uptimeMillis();
        if (context == null) {
            remoteService = NoOpRemoteAccessService.INSTANCE;
            lastResolvedFallback = true;
            return;
        }
        if (!isProviderInstalled(context)) {
            
            
            remoteService = NoOpRemoteAccessService.INSTANCE;
            lastResolvedFallback = true;
            return;
        }
        try {
            Bundle bundle = new Bundle();
            bundle.putString(KEY_APP_PACKAGE_NAME, context.getPackageName());
            Bundle bundleCall = context.getContentResolver().call(CONTENT_URI, METHOD_GET_REMOTE_ACCESS_BINDER, (String) null, bundle);
            IBinder binder = bundleCall != null ? bundleCall.getBinder(KEY_BINDER) : null;
            IRemoteAccessService iRemoteAccessServiceAsInterface = binder != null ? IRemoteAccessService.Stub.asInterface(binder) : null;
            if (iRemoteAccessServiceAsInterface == null) {
                remoteService = NoOpRemoteAccessService.INSTANCE;
                lastResolvedFallback = true;
            } else {
                linkDeathRecipient(binder);
                remoteService = iRemoteAccessServiceAsInterface;
                lastResolvedFallback = false;
            }
        } catch (Throwable th) {
            remoteService = NoOpRemoteAccessService.INSTANCE;
            lastResolvedFallback = true;
            Log.w(TAG, "无法解析远程访问 Binder", th);
        }
    }

    private static void linkDeathRecipient(IBinder iBinder) {
        try {
            iBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public final void binderDied() {
                    RemoteAccessBridge.lambda$linkDeathRecipient$0();
                }
            }, 0);
        } catch (Throwable th) {
            Log.w(TAG, "无法监听远程 Binder 死亡", th);
        }
    }

    static void lambda$linkDeathRecipient$0() {
        remoteService = NoOpRemoteAccessService.INSTANCE;
        lastResolvedFallback = true;
    }

    private static void onRemoteFailure(RemoteException remoteException) {
        remoteService = NoOpRemoteAccessService.INSTANCE;
        lastResolvedFallback = true;
        Log.w(TAG, "远程访问调用失败", remoteException);
    }

    static final class NoOpRemoteAccessService extends IRemoteAccessService.Stub {
        private static final NoOpRemoteAccessService INSTANCE = new NoOpRemoteAccessService();

        @Override
        public boolean deleteRemoteFile(String str, String str2) {
            return false;
        }

        @Override
        public void deleteRemotePreferences(String str, String str2) {
        }

        @Override
        public String[] listRemoteFiles(String str) {
            return new String[0];
        }

        @Override
        public ParcelFileDescriptor openRemoteFile(String str, String str2) {
            return null;
        }

        @Override
        public void updateRemotePreferences(String str, String str2, Bundle bundle) {
        }

        private NoOpRemoteAccessService() {
        }

        @Override
        public Bundle requestRemotePreferences(String str, String str2) {
            Bundle bundle = new Bundle();
            bundle.putBundle(RemoteAccessBridge.KEY_PREF_BUNDLE, new Bundle());
            return bundle;
        }
    }
}
