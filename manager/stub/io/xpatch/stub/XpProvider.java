package io.xpatch.stub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public class XpProvider extends ContentProvider {

    private static final String TAG = "Boot";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            Log.w(TAG, "入口 provider 上下文为空，跳过注入");
            return false;
        }
        try {
            ApplicationInfo info = context.getApplicationInfo();
            boolean injected = AppEntry.ensureInjected(context.getClassLoader(), info);
            if (!injected) {
                Log.e(TAG, "入口注入失败：" + context.getPackageName());
            }
            return injected;
        } catch (Throwable t) {
            Log.e(TAG, "入口注入异常", t);
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
