package io.xpatch.patch

import android.content.Context
import com.android.apksig.DefaultApkSignerEngine
import com.android.apksig.util.DataSources
import com.android.apksig.util.RunnablesExecutor
import io.xpatch.data.SignatureStore
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipFile

class ApkSigner(private val context: Context) {

    private val signatureStore = SignatureStore(context)

    fun sign(input: File, output: File, profile: SignatureStore.Profile) {
        val (key, certificate) = load(profile)

        val signerConfig = DefaultApkSignerEngine.SignerConfig.Builder(
            profile.name,
            key,
            listOf(certificate),
        ).build()
        val engine = DefaultApkSignerEngine.Builder(listOf(signerConfig), MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        engine.setExecutor(RunnablesExecutor.SINGLE_THREADED)

        RandomAccessFile(input, "r").use { source ->
            val length = source.length()
            require(length > EOCD_SIZE) { "输入产物过小，无法签名" }
            val tailStart = maxOf(0L, length - EOCD_SEARCH_WINDOW)
            val tail = ByteArray((length - tailStart).toInt())
            source.seek(tailStart)
            source.readFully(tail)
            val found = findEocd(tail, length)
                ?: error("输入产物缺少 ZIP 中央目录（不是有效 APK）")
            val eocdOffset = tailStart + found
            val cdSize = readU32(tail, found + 12)
            val cdOffset = readU32(tail, found + 16)
            require(cdOffset > 0 && cdSize > 0 && cdOffset + cdSize == eocdOffset) {
                "输入产物中央目录结构异常"
            }
            val eocdSize = (length - eocdOffset).toInt()
            require(eocdSize.toLong() in EOCD_SIZE.toLong()..MAX_EOCD_SIZE) { "输入产物 EOCD 结构异常" }

            ZipFile(input).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    engine.inputJarEntry(entries.nextElement().name)
                }
            }

            val contents = DataSources.asDataSource(source, 0, cdOffset)
            val centralDirectory = DataSources.asDataSource(source, cdOffset, cdSize)
            val eocd = DataSources.asDataSource(source, eocdOffset, eocdSize.toLong())
            val request = engine.outputZipSections2(contents, centralDirectory, eocd)
            val padding = request.paddingSizeBeforeApkSigningBlock
            val block = request.apkSigningBlock
            request.done()

            val eocdBytes = ByteArray(eocdSize)
            val centralOffset = cdOffset + padding + block.size
            source.seek(eocdOffset)
            source.readFully(eocdBytes)
            putU32(eocdBytes, 16, centralOffset)

            FileOutputStream(output, false).buffered(BUFFER_SIZE).use { out ->
                source.seek(0)
                copyRange(source, cdOffset, out)
                if (padding > 0) out.write(ByteArray(padding))
                out.write(block)
                source.seek(cdOffset)
                copyRange(source, cdSize, out)
                out.write(eocdBytes)
            }
        }
        engine.outputDone()

        val growth = output.length() - input.length()
        require(growth in 0..MAX_SIGNATURE_OVERHEAD) {
            "签名产物异常膨胀 $growth B：签名器重写了 ZIP 条目，内嵌原包会被复制一份"
        }
    }

    private fun copyRange(source: RandomAccessFile, length: Long, out: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var left = length
        var copied = 0L
        while (left > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
            copied += read
        }
        return copied
    }

    private fun findEocd(tail: ByteArray, totalLength: Long): Int? {
        val tailStart = totalLength - tail.size
        for (index in tail.size - EOCD_SIZE downTo 0) {
            if (readU32(tail, index) != EOCD_SIG) continue
            val comment = tail[index + 20].toInt() and 0xFF or
                ((tail[index + 21].toInt() and 0xFF) shl 8)
            if (tailStart + index + EOCD_SIZE + comment == totalLength) {
                return index
            }
        }
        return null
    }

    private fun readU32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
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
        const val EOCD_SIZE = 22
        const val EOCD_SIG = 0x06054b50L
        const val EOCD_SEARCH_WINDOW = 1024L * 1024L
        const val MAX_EOCD_SIZE = 1024L * 1024L
        const val MAX_SIGNATURE_OVERHEAD = 256L * 1024L
        const val BUFFER_SIZE = 256 * 1024
    }
}