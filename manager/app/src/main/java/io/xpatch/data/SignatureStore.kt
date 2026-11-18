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
        Triple("lspatch", "LSPatch", "lspatch"),
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

    fun exportCustomProfiles(): JSONArray {
        val array = JSONArray()
        customProfiles().forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("password", profile.password)
                    put("algorithm", profile.algorithm)
                    put("file", profile.customFile?.let { File(it).name }.orEmpty())
                },
            )
        }
        return array
    }

    fun customBinFiles(): Map<String, File> = customProfiles()
        .mapNotNull { profile -> profile.customFile?.let { File(it) } }
        .filter { it.isFile }
        .associateBy { it.name }

    fun restoreCustom(profiles: JSONArray, bins: Map<String, ByteArray>): Int {
        val dir = File(context.filesDir, "signatures").apply { mkdirs() }
        val restored = ArrayList<Profile>()
        for (index in 0 until profiles.length()) {
            val item = profiles.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val target = File(dir, "$id.bin")
            val fileField = item.optString("file").substringAfterLast('/').removeSuffix(BIN_SUFFIX)
            val bin = bins[id] ?: bins[fileField]
            if (bin != null) {
                runCatching { target.writeBytes(bin) }.getOrElse { continue }
            } else if (!target.isFile) {
                continue
            }
            restored.add(
                Profile(
                    id = id,
                    name = item.optString("name").ifBlank { id },
                    kind = Kind.CUSTOM,
                    customFile = target.absolutePath,
                    password = item.optString("password"),
                    algorithm = item.optString("algorithm").ifBlank { "RSA" },
                ),
            )
        }
        persist(restored)
        val keep = restored.mapNotNullTo(HashSet()) { it.customFile?.let { path -> File(path).name } }
        dir.listFiles { file -> file.isFile && file.name !in keep }?.forEach { it.delete() }
        return restored.size
    }

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

    fun importKeystore(
        name: String,
        source: File,
        password: String,
        certFile: File?,
        keyFile: File?,
    ): Profile {
        val dir = File(context.filesDir, "signatures").apply { mkdirs() }
        val id = "custom_" + System.currentTimeMillis()
        val target = File(dir, "$id.bin")
        if (certFile != null && keyFile != null) {
            val certData = certFile.readBytes()
            val keyData = keyFile.readBytes()
            val cert = java.security.cert.CertificateFactory
                .getInstance("X.509")
                .generateCertificate(certData.inputStream()) as java.security.cert.X509Certificate
            val sanitized = stripPemHeader(keyData)
            val publicKey = cert.publicKey
            val key = runCatching {
                val factory = java.security.KeyFactory.getInstance(publicKey.algorithm)
                factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(sanitized))
            }.recoverCatching {
                val factory = java.security.KeyFactory.getInstance("RSA")
                factory.generatePrivate(java.security.spec.RSAPrivateCrtKeySpec::class.java.let {
                    java.security.spec.PKCS8EncodedKeySpec(sanitized)
                })
            }.getOrElse {
                throw IllegalArgumentException("无法解析私钥（仅支持 PKCS8 DER/PEM）：${it.message}")
            }
            val store = java.security.KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("import", key, password.toCharArray(), arrayOf(cert))
            verifyKeyCertMatch(key, cert)
            target.outputStream().use { out ->
                store.store(out, password.toCharArray())
            }
        } else {
            source.copyTo(target, overwrite = true)
        }
        val profile = Profile(
            id = id,
            name = name,
            kind = Kind.CUSTOM,
            customFile = target.absolutePath,
            password = password,
            algorithm = certAlgorithmOf(certFile, source, password),
        )
        save(profile)
        return profile
    }

    private fun verifyKeyCertMatch(key: java.security.PrivateKey, cert: java.security.cert.X509Certificate) {
        val mismatch = runCatching {
            val sig = java.security.Signature.getInstance(
                when (cert.publicKey.algorithm) {
                    "EC" -> "SHA256withECDSA"
                    else -> "SHA256withRSA"
                },
            )
            val sample = ByteArray(64)
            sig.initSign(key)
            sig.update(sample)
            val signed = sig.sign()
            sig.initVerify(cert)
            sig.update(sample)
            !sig.verify(signed)
        }.getOrDefault(true)
        if (mismatch) {
            throw IllegalArgumentException("私钥与证书不匹配，请确认两个文件来自同一对密钥")
        }
    }

    private fun stripPemHeader(data: ByteArray): ByteArray {
        val text = String(data, Charsets.US_ASCII).trim()
        if (!text.startsWith("-----BEGIN")) {
            return data
        }
        val body = text
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }

    private fun certAlgorithmOf(certFile: File?, source: File, password: String): String {
        if (certFile != null) {
            return runCatching {
                val cert = java.security.cert.CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(certFile.inputStream())
                cert.publicKey.algorithm
            }.getOrDefault("RSA")
        }
        return runCatching {
            val store = loadAnyStore(source, password) ?: return@runCatching "RSA"
            val alias = store.aliases().toList().firstOrNull() ?: return@runCatching "RSA"
            val cert = store.getCertificate(alias) ?: return@runCatching "RSA"
            cert.publicKey.algorithm
        }.getOrDefault("RSA")
    }

    private fun loadAnyStore(file: File, password: String): java.security.KeyStore? {
        val types = listOf("PKCS12", "BKS", "JKS")
        val providers = listOf("BC", "BC", null)
        for (i in types.indices) {
            val result = runCatching {
                val store = if (providers[i] != null) {
                    java.security.KeyStore.getInstance(types[i], providers[i])
                } else {
                    java.security.KeyStore.getInstance(types[i])
                }
                file.inputStream().use { store.load(it, password.toCharArray()) }
                store
            }
            if (result.isSuccess && result.getOrNull() != null) {
                return result.getOrNull()
            }
        }
        return null
    }

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
        private const val BIN_SUFFIX = ".bin"

        const val BUILTIN_XPATCH = "xpatch"
    }
}
