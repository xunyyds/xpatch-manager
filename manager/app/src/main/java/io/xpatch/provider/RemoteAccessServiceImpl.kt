package io.xpatch.provider

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import io.xpatch.remote.IRemoteAccessService
import java.io.File
import java.security.MessageDigest

class RemoteAccessServiceImpl(
    private val context: Context,
    private val callerPackage: String,
) : IRemoteAccessService.Stub() {

    override fun requestRemotePreferences(modulePackageName: String, group: String): Bundle {
        val map = HashMap<String, Any>()
        runCatching {
            prefs(modulePackageName, group).all.forEach { (key, value) ->
                if (value != null) {
                    map[key] = value
                }
            }
        }.onFailure { Log.w(TAG, "read remote preferences failed: $modulePackageName/$group", it) }
        return Bundle().apply { putSerializable(KEY_MAP, map) }
    }

    override fun updateRemotePreferences(modulePackageName: String, group: String, diff: Bundle?) {
        if (diff == null) {
            return
        }
        runCatching {
            val editor = prefs(modulePackageName, group).edit()
            if (diff.getBoolean(KEY_CLEAR, false)) {
                editor.clear()
            }
            diff.getStringArrayList(KEY_DELETE)?.forEach { key -> editor.remove(key) }
            diff.getBundle(KEY_PUT)?.let { put ->
                for (key in put.keySet()) {
                    when (val value = put.get(key)) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is ArrayList<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> editor.putString(key, value.toString())
                    }
                }
            }
            if (!editor.commit()) {
                throw IllegalStateException("commit returned false")
            }
        }.onFailure { Log.w(TAG, "update remote preferences failed: $modulePackageName/$group", it) }
    }

    override fun deleteRemotePreferences(modulePackageName: String, group: String) {
        runCatching { context.deleteSharedPreferences(prefsName(modulePackageName, group)) }
            .onFailure { Log.w(TAG, "delete remote preferences failed: $modulePackageName/$group", it) }
    }

    override fun listRemoteFiles(modulePackageName: String): Array<String> =
        runCatching { moduleDir(modulePackageName).list() ?: emptyArray() }
            .getOrDefault(emptyArray())

    override fun openRemoteFile(modulePackageName: String, path: String?): ParcelFileDescriptor? {
        if (path.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val file = safeFile(modulePackageName, path) ?: return@runCatching null
            file.parentFile?.mkdirs()
            if (!file.exists() && !file.createNewFile()) {
                return@runCatching null
            }
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            )
        }.onFailure { Log.w(TAG, "open remote file failed: $modulePackageName/$path", it) }.getOrNull()
    }

    override fun deleteRemoteFile(modulePackageName: String, path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }
        return runCatching { safeFile(modulePackageName, path)?.delete() == true }.getOrDefault(false)
    }

    private fun prefs(modulePackageName: String, group: String) =
        context.getSharedPreferences(prefsName(modulePackageName, group), Context.MODE_PRIVATE)

    private fun prefsName(modulePackageName: String, group: String): String =
        "xp_remote_" + digest(callerPackage + "|" + modulePackageName + "|" + group)

    private fun moduleDir(modulePackageName: String): File =
        File(File(File(context.filesDir, "remote-files"), digest(callerPackage)), digest(modulePackageName))
            .apply { mkdirs() }

    private fun safeFile(modulePackageName: String, path: String): File? {
        val base = moduleDir(modulePackageName).canonicalFile
        val file = File(base, path).canonicalFile
        return if (file.path == base.path || !file.path.startsWith(base.path + File.separator)) {
            null
        } else {
            file
        }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "XPatchRemote"
        private const val KEY_MAP = "map"
        private const val KEY_CLEAR = "clear"
        private const val KEY_DELETE = "delete"
        private const val KEY_PUT = "put"
    }
}
