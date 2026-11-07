package io.xpatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SignatureStore(private val context: Context) {

    enum class Kind { BUILTIN, CUSTOM }

    data class Profile(
        val id: String,
        val name: String,
        val kind: Kind,
        val builtinId: String? = null,
        val customFile: String? = null,
        val password: String = "",
        val algorithm: String = "RSA",
    )

    private val prefs = context.getSharedPreferences("signatures", Context.MODE_PRIVATE)

    fun builtinProfiles(): List<Profile> = listOf(
        Triple(BUILTIN_XPATCH, "XPatch", BUILTIN_XPATCH),
        Triple("fpa", "FPA", "fpa"),
        Triple("np", "NPatch", "np"),
        Triple("npatch", "NPatch 官方密钥", "npatch"),
    ).filter { isBuiltinAvailable(it.third) }
        .map { (id, name, builtinId) ->
            Profile(id = id, name = name, kind = Kind.BUILTIN, builtinId = builtinId)
        }

    fun customProfiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Profile(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            kind = Kind.CUSTOM,
                            customFile = item.optString("file"),
                            password = item.optString("password"),
                            algorithm = item.optString("algorithm", "RSA"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun allProfiles(): List<Profile> = builtinProfiles() + customProfiles()

    fun defaultProfileId(): String = prefs.getString(KEY_DEFAULT, BUILTIN_XPATCH) ?: BUILTIN_XPATCH

    fun setDefaultProfile(id: String) = prefs.edit().putString(KEY_DEFAULT, id).apply()

    fun certificateBytes(profile: Profile): ByteArray? = runCatching {
        if (profile.kind != Kind.BUILTIN) return null
        context.assets.open(certAssetPath(profile)).use { it.readBytes() }
    }.getOrNull()

    private fun certAssetPath(profile: Profile): String {
        val id = profile.builtinId ?: BUILTIN_XPATCH
        return if (id == BUILTIN_XPATCH) "xp/cert.der" else "xp/sign/$id/cert.der"
    }

    private fun keyAssetPath(profile: Profile): String {
        val id = profile.builtinId ?: BUILTIN_XPATCH
        return if (id == BUILTIN_XPATCH) "xp/key.pk8" else "xp/sign/$id/key.pk8"
    }

    private fun isBuiltinAvailable(id: String): Boolean = runCatching {
        val profile = Profile(id = id, name = id, kind = Kind.BUILTIN, builtinId = id)
        context.assets.open(keyAssetPath(profile)).close()
        context.assets.open(certAssetPath(profile)).close()
        true
    }.getOrDefault(false)

    fun keyBytes(profile: Profile): ByteArray {
        require(profile.kind == Kind.BUILTIN) { "只有内置签名使用 assets 密钥" }
        val path = keyAssetPath(profile)
        check(isBuiltinAvailable(profile.builtinId ?: BUILTIN_XPATCH)) { "缺少内置签名密钥：$path" }
        return context.assets.open(path).use { it.readBytes() }
    }

    fun certBytes(profile: Profile): ByteArray {
        require(profile.kind == Kind.BUILTIN) { "只有内置签名使用 assets 证书" }
        val path = certAssetPath(profile)
        check(isBuiltinAvailable(profile.builtinId ?: BUILTIN_XPATCH)) { "缺少内置签名证书：$path" }
        return context.assets.open(path).use { it.readBytes() }
    }

    fun defaultProfile(): Profile =
        allProfiles().firstOrNull { it.id == defaultProfileId() } ?: builtinProfiles().first()

    fun addCustom(name: String, source: File, password: String, algorithm: String): Profile {
        val dir = File(context.filesDir, "signatures").apply { mkdirs() }
        val id = "custom_" + System.currentTimeMillis()
        val target = File(dir, "$id.bin")
        source.copyTo(target, overwrite = true)

        val profile = Profile(
            id = id,
            name = name,
            kind = Kind.CUSTOM,
            customFile = target.absolutePath,
            password = password,
            algorithm = algorithm,
        )
        save(profile)
        return profile
    }

    fun updateCustom(profile: Profile) = save(profile)

    fun deleteCustom(id: String) {
        val list = customProfiles().filterNot { it.id == id }
        persist(list)
        File(context.filesDir, "signatures/$id.bin").delete()
        if (defaultProfileId() == id) setDefaultProfile(BUILTIN_XPATCH)
    }

    private fun save(profile: Profile) {
        val list = customProfiles().filterNot { it.id == profile.id } + profile
        persist(list)
    }

    private fun persist(list: List<Profile>) {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("file", item.customFile ?: "")
                    put("password", item.password)
                    put("algorithm", item.algorithm)
                },
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_DEFAULT = "default"

        const val BUILTIN_XPATCH = "xpatch"
    }
}
