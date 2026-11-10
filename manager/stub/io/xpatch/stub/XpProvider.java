package io.xpatch.stub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public class XpProvider extends ContentProvider {

    private static final String TAG = "XPatch";

    /**
     * 备用入口：entryHook=true 时打包器把本 provider 插进目标清单。
     *
     * 不变量：返回值必须是 {@link AppEntry#ensureInjected(ClassLoader, ApplicationInfo)} 的真实结果。
     * 恒 true 会让注入失败彻底静默（应用照常启动、模块一个都不加载、日志页又看不到 bootstrap.log），
     * 用户侧表现是"打包成功、打开正常、模块就是不生效"。返回 false 时系统至少会把
     * "provider 创建失败"写进 logcat / 崩溃记录，配合引导桩写下的 bootstrap.log 才能定位。
     */
    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            Log.w(TAG, "XpProvider 上下文为空，跳过注入");
            return false;
        }
        try {
            ApplicationInfo info = context.getApplicationInfo();
            boolean injected = AppEntry.ensureInjected(context.getClassLoader(), info);
            if (!injected) {
                // 失败原因已经由 AppEntry.fatal() 落到 bootstrap.log，这里只补一条 logcat 便于抓现场。
                Log.e(TAG, "XpProvider 入口注入失败：" + context.getPackageName());
            }
            return injected;
        } catch (Throwable t) {
            Log.e(TAG, "XpProvider 入口注入异常", t);
            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
