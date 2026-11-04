package io.xpatch.patch

import android.content.Context
import com.android.apksig.ApkSigner
import io.xpatch.data.SignatureStore
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

class ApkSigner(private val context: Context) {

    private val signatureStore = SignatureStore(context)

    fun sign(input: File, output: File, profile: SignatureStore.Profile) {
        val (key, certificate) = load(profile)

        val config = ApkSigner.SignerConfig.Builder(profile.name, key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(MIN_SDK)
            .build()
            .sign()
    }

    private fun load(profile: SignatureStore.Profile): Pair<PrivateKey, X509Certificate> {
        return when (profile.kind) {
            SignatureStore.Kind.BUILTIN -> {
                val key = KeyFactory.getInstance(profile.algorithm)
                    .generatePrivate(PKCS8EncodedKeySpec(signatureStore.keyBytes(profile)))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(signatureStore.certBytes(profile).inputStream()) as X509Certificate
                key to cert
            }

            SignatureStore.Kind.CUSTOM -> {
                val file = profile.customFile?.let { File(it) }
                    ?: error("自定义签名配置缺少密钥文件")

                val keyStore = loadKeyStore(file, profile.password)
                    ?: error("无法读取密钥库，请检查格式与口令")

                val alias = keyStore.aliases().toList().firstOrNull()
                    ?: error("密钥库中没有条目")

                val key = keyStore.getKey(alias, profile.password.toCharArray()) as PrivateKey
                val cert = keyStore.getCertificate(alias) as X509Certificate
                key to cert
            }
        }
    }

    private fun loadKeyStore(file: File, password: String): KeyStore? {
        val types = listOf("PKCS12", "BKS", "JKS")
        val providers = listOf("BC", "BC", null)

        for (i in types.indices) {
            val type = types[i]
            val provider = providers.getOrElse(i) { null }
            val result = runCatching {
                val store = if (provider != null) KeyStore.getInstance(type, provider)
                else KeyStore.getInstance(type)
                file.inputStream().use { store.load(it, password.toCharArray()) }
                store
            }
            if (result.isSuccess) return result.getOrNull()
        }
        return null
    }

    private companion object {
        const val MIN_SDK = 26
    }
}
