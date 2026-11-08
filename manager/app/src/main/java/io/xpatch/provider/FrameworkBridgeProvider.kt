package io.xpatch.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

class FrameworkBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_REMOTE_ACCESS_BINDER) {
            return null
        }
        val ctx = context?.applicationContext ?: return null
        val declared = extras?.getString(KEY_APP_PACKAGE_NAME)
        val caller = resolveCallerPackage(ctx, declared) ?: run {
            Log.w(TAG, "reject call: cannot resolve caller package (declared=$declared)")
            return null
        }
        val service = RemoteAccessServiceImpl(ctx, caller)
        return Bundle().apply { putBinder(KEY_BINDER, service) }
    }

    private fun resolveCallerPackage(ctx: Context, declared: String?): String? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) {
            return declared ?: ctx.packageName
        }
        val packages = runCatching { ctx.packageManager.getPackagesForUid(uid) }.getOrNull()
        if (packages.isNullOrEmpty()) {
            return null
        }
        return if (declared != null && packages.contains(declared)) declared else packages[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "XPatchRemote"
        private const val METHOD_GET_REMOTE_ACCESS_BINDER = "getRemoteAccessBinder"
        private const val KEY_APP_PACKAGE_NAME = "appPackageName"
        private const val KEY_BINDER = "binder"
    }
}
