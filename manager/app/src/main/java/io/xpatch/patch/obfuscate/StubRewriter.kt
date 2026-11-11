package io.xpatch.patch.obfuscate

import java.security.MessageDigest
import java.util.Random
import java.util.zip.Adler32

object StubRewriter {

    const val DEFAULT_FACTORY = "io.xpatch.stub.AppEntry"
    const val DEFAULT_PROVIDER = "io.xpatch.stub.XpProvider"

    private const val FACTORY_INTERNAL = "io/xpatch/stub/AppEntry"
    private const val PROVIDER_INTERNAL = "io/xpatch/stub/XpProvider"

    private val STUB_PREFIX = "io/xpatch/stub/".toByteArray(Charsets.US_ASCII)

    data class Result(
        val bytes: ByteArray,
        val factoryDashed: String,
        val providerDashed: String,
        val renamed: Boolean,
        val reason: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return bytes.contentEquals(other.bytes) &&
                factoryDashed == other.factoryDashed &&
                providerDashed == other.providerDashed &&
                renamed == other.renamed &&
                reason == other.reason
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + factoryDashed.hashCode()
            result = 31 * result + providerDashed.hashCode()
            result = 31 * result + renamed.hashCode()
            result = 31 * result + (reason?.hashCode() ?: 0)
            return result
        }
    }

    fun skipped(dex: ByteArray, reason: String?): Result = Result(
        bytes = dex,
        factoryDashed = DEFAULT_FACTORY,
        providerDashed = DEFAULT_PROVIDER,
        renamed = false,
        reason = reason,
    )

    fun rewrite(
        dex: ByteArray,
        seed: Long,
        appEntryInternal: String = FACTORY_INTERNAL,
        providerInternal: String = PROVIDER_INTERNAL,
        preferredFactory: String? = null,
        preferredProvider: String? = null,
    ): Result {
        if (dex.size < HEADER_SIZE) return skipped(dex, "dex 头部不完整")
        if (!isDexMagic(dex)) return skipped(dex, "不是 dex 文件")
        if (u32(dex, FILE_SIZE_OFFSET) != dex.size.toLong()) {
            return skipped(dex, "dex 声明体积与实际字节数不符")
        }
        val stringIdsSize = u32(dex, STRING_IDS_SIZE_OFFSET).toInt()
        val stringIdsOffset = u32(dex, STRING_IDS_OFFSET).toInt()
        if (stringIdsSize <= 0 || stringIdsOffset < HEADER_SIZE ||
            stringIdsOffset.toLong() + stringIdsSize.toLong() * 4 > dex.size
        ) {
            return skipped(dex, "字符串表越界")
        }

        val factoryDescriptor = "L$appEntryInternal;"
        val providerDescriptor = "L$providerInternal;"
        val factoryHits = countOccurrences(dex, factoryDescriptor.toByteArray(Charsets.US_ASCII))
        if (factoryHits != 1) {
            return skipped(dex, "$appEntryInternal 在 dex 里出现 $factoryHits 次（需要恰好 1 次）")
        }
        val providerHits = countOccurrences(dex, providerDescriptor.toByteArray(Charsets.US_ASCII))
        if (providerHits != 1) {
            return skipped(dex, "$providerInternal 在 dex 里出现 $providerHits 次（需要恰好 1 次）")
        }

        val table = parseTable(dex, stringIdsSize, stringIdsOffset) ?: return skipped(dex, "字符串表结构损坏")
        if (!isSorted(table.texts)) return skipped(dex, "字符串表不是按 UTF-16 码元有序排列，无法安全等长替换")

        val factoryIndex = table.texts.indexOf(factoryDescriptor)
        if (factoryIndex < 0) return skipped(dex, "$appEntryInternal 不在字符串表里")
        val providerIndex = table.texts.indexOf(providerDescriptor)
        if (providerIndex < 0) return skipped(dex, "$providerInternal 不在字符串表里")
        if (table.ends[factoryIndex] - table.starts[factoryIndex] != factoryDescriptor.length) {
            return skipped(dex, "$appEntryInternal 不是纯 ASCII 描述符")
        }
        if (table.ends[providerIndex] - table.starts[providerIndex] != providerDescriptor.length) {
            return skipped(dex, "$providerInternal 不是纯 ASCII 描述符")
        }

        val factoryPrev = if (factoryIndex > 0) table.texts[factoryIndex - 1] else null
        val factoryNext = if (factoryIndex + 1 < table.size) table.texts[factoryIndex + 1] else null
        val providerPrev = if (providerIndex > 0) table.texts[providerIndex - 1] else null
        val providerNext = if (providerIndex + 1 < table.size) table.texts[providerIndex + 1] else null
        if (factoryPrev == null && factoryNext == null) return skipped(dex, "描述符位于字符串表首尾，没有有序窗口")
        if (providerPrev == null && providerNext == null) return skipped(dex, "描述符位于字符串表首尾，没有有序窗口")

        val random = Random(seed)
        var attempt = 0
        val preferredFactoryName = preferredFactory?.let { innerNameOf(it, factoryDescriptor.length - 2) }
        val preferredProviderName = preferredProvider?.let { innerNameOf(it, providerDescriptor.length - 2) }
        var usePreferred = preferredFactoryName != null && preferredProviderName != null
        while (attempt < MAX_NAME_ATTEMPTS) {
            attempt++
            val factoryName: String?
            val providerName: String?
            if (usePreferred) {
                factoryName = preferredFactoryName
                providerName = preferredProviderName
                usePreferred = false
            } else {
                factoryName = randomInnerName(random, factoryDescriptor.length - 2, firstCharHint(factoryPrev, factoryNext))
                providerName = randomInnerName(random, providerDescriptor.length - 2, firstCharHint(providerPrev, providerNext))
            }
            if (factoryName == null || providerName == null) continue
            if (factoryName == appEntryInternal || providerName == providerInternal) continue
            if (factoryName == providerName) continue
            val patchedFactory = "L$factoryName;"
            val patchedProvider = "L$providerName;"
            if (factoryPrev != null && factoryPrev.compareTo(patchedFactory) >= 0) continue
            if (factoryNext != null && factoryNext.compareTo(patchedFactory) <= 0) continue
            if (providerPrev != null && providerPrev.compareTo(patchedProvider) >= 0) continue
            if (providerNext != null && providerNext.compareTo(patchedProvider) <= 0) continue
            if (factoryIndex < providerIndex && patchedFactory.compareTo(patchedProvider) >= 0) continue
            if (providerIndex < factoryIndex && patchedProvider.compareTo(patchedFactory) >= 0) continue
            val patchedTexts = table.texts.copyOf()
            patchedTexts[factoryIndex] = patchedFactory
            patchedTexts[providerIndex] = patchedProvider
            if (!isSorted(patchedTexts)) continue

            val candidate = dex.copyOf()
            System.arraycopy(
                patchedFactory.toByteArray(Charsets.US_ASCII), 0, candidate,
                table.starts[factoryIndex], patchedFactory.length,
            )
            System.arraycopy(
                patchedProvider.toByteArray(Charsets.US_ASCII), 0, candidate,
                table.starts[providerIndex], patchedProvider.length,
            )
            if (countOccurrences(candidate, STUB_PREFIX) != 0) continue
            if (countOccurrences(candidate, patchedFactory.toByteArray(Charsets.US_ASCII)) != 1) continue
            if (countOccurrences(candidate, patchedProvider.toByteArray(Charsets.US_ASCII)) != 1) continue
            refreshHeader(candidate)
            return Result(
                bytes = candidate,
                factoryDashed = factoryName.replace('/', '.'),
                providerDashed = providerName.replace('/', '.'),
                renamed = true,
                reason = null,
            )
        }
        return skipped(dex, "连续 $MAX_NAME_ATTEMPTS 次没摇出可用类名（有序窗口太窄）")
    }

    private fun refreshHeader(dex: ByteArray) {
        val signature = MessageDigest.getInstance("SHA-1").apply {
            update(dex, HASH_OFFSET, dex.size - HASH_OFFSET)
        }.digest()
        System.arraycopy(signature, 0, dex, SIGNATURE_OFFSET, SIGNATURE_SIZE)
        val adler = Adler32().apply { update(dex, SIGNATURE_OFFSET, dex.size - SIGNATURE_OFFSET) }
        putU32(dex, CHECKSUM_OFFSET, adler.value)
    }

    private fun randomInnerName(random: Random, innerLength: Int, firstHint: IntRange?): String? {
        if (innerLength < 3) return null
        val segments = when {
            innerLength < 5 -> 1
            random.nextInt(10) < 1 -> 1
            random.nextBoolean() -> 2
            else -> 3
        }
        val chars = innerLength - (segments - 1)
        if (chars < segments) return null
        val lengths = IntArray(segments) { 1 }
        repeat(chars - segments) { lengths[random.nextInt(segments)]++ }
        val text = StringBuilder(innerLength)
        for (index in 0 until segments) {
            if (index > 0) text.append('/')
            val size = lengths[index]
            if (index == segments - 1) {
                text.append(UPPER[random.nextInt(UPPER.length)])
                repeat(size - 1) { text.append(UPPER_TAIL[random.nextInt(UPPER_TAIL.length)]) }
            } else {
                val firstCode = if (index == 0 && firstHint != null) {
                    firstHint.first + random.nextInt(firstHint.last - firstHint.first + 1)
                } else {
                    LOWER[random.nextInt(LOWER.length)].code
                }
                text.append(firstCode.toChar())
                repeat(size - 1) { text.append(LOWER_TAIL[random.nextInt(LOWER_TAIL.length)]) }
            }
        }
        return text.toString()
    }

    private fun firstCharHint(lower: String?, upper: String?): IntRange? {
        if (lower == null || upper == null || lower.length < 2 || upper.length < 2) return null
        if (!lower.startsWith("L") || !upper.startsWith("L")) return null
        val from = maxOf(lower[1].code, 'a'.code)
        val to = minOf(upper[1].code, 'z'.code)
        if (from > to) return null
        return from..to
    }

    private fun innerNameOf(dashed: String, innerLength: Int): String? {
        val internal = dashed.replace('.', '/')
        if (internal.length != innerLength) return null
        val legal = internal.all { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '/' || char == '_' || char == '$'
        }
        return if (legal) internal else null
    }

    private fun parseTable(dex: ByteArray, size: Int, offset: Int): Table? {
        val starts = IntArray(size)
        val ends = IntArray(size)
        val texts = arrayOfNulls<String>(size)
        for (index in 0 until size) {
            val stringOffset = u32(dex, offset + index * 4).toInt()
            if (stringOffset <= 0 || stringOffset >= dex.size) return null
            val decoded = readUleb128(dex, stringOffset) ?: return null
            val units = decoded.first
            val payload = decoded.second
            val decodedText = decodeMutf8(dex, payload, units) ?: return null
            val end = payload + decodedText.second
            if (end >= dex.size || dex[end].toInt() != 0) return null
            starts[index] = payload
            ends[index] = end
            texts[index] = decodedText.first
        }
        @Suppress("UNCHECKED_CAST")
        return Table(starts, ends, texts as Array<String>)
    }

    private fun decodeMutf8(bytes: ByteArray, start: Int, units: Int): Pair<String, Int>? {
        val out = CharArray(units)
        var produced = 0
        var index = start
        while (produced < units) {
            if (index >= bytes.size) return null
            val first = bytes[index].toInt() and 0xFF
            when {
                first and 0x80 == 0 -> {
                    out[produced++] = first.toChar()
                    index += 1
                }

                first and 0xE0 == 0xC0 -> {
                    if (index + 1 >= bytes.size) return null
                    val second = bytes[index + 1].toInt() and 0xFF
                    if (second and 0xC0 != 0x80) return null
                    out[produced++] = (((first and 0x1F) shl 6) or (second and 0x3F)).toChar()
                    index += 2
                }

                first and 0xF0 == 0xE0 -> {
                    if (index + 2 >= bytes.size) return null
                    val second = bytes[index + 1].toInt() and 0xFF
                    val third = bytes[index + 2].toInt() and 0xFF
                    if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80) return null
                    out[produced++] = (
                        ((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
                        ).toChar()
                    index += 3
                }

                first and 0xF8 == 0xF0 -> {
                    if (index + 3 >= bytes.size || produced + 2 > units) return null
                    val second = bytes[index + 1].toInt() and 0xFF
                    val third = bytes[index + 2].toInt() and 0xFF
                    val fourth = bytes[index + 3].toInt() and 0xFF
                    if (second and 0xC0 != 0x80 || third and 0xC0 != 0x80 || fourth and 0xC0 != 0x80) return null
                    val codePoint = ((first and 0x07) shl 18) or ((second and 0x3F) shl 12) or
                        ((third and 0x3F) shl 6) or (fourth and 0x3F)
                    if (codePoint < 0x10000) return null
                    val offsetCodePoint = codePoint - 0x10000
                    out[produced++] = (0xD800 + (offsetCodePoint shr 10)).toChar()
                    out[produced++] = (0xDC00 + (offsetCodePoint and 0x3FF)).toChar()
                    index += 4
                }

                else -> return null
            }
        }
        return String(out) to (index - start)
    }

    private fun isSorted(texts: Array<String>): Boolean {
        for (index in 0 until texts.size - 1) {
            if (texts[index].compareTo(texts[index + 1]) > 0) return false
        }
        return true
    }

    private class Table(val starts: IntArray, val ends: IntArray, val texts: Array<String>) {
        val size: Int get() = texts.size
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return 0
        var count = 0
        var index = 0
        val limit = haystack.size - needle.size
        while (index <= limit) {
            if (haystack[index] == needle[0]) {
                var matched = true
                for (offset in 1 until needle.size) {
                    if (haystack[index + offset] != needle[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    count++
                    index += needle.size
                    continue
                }
            }
            index++
        }
        return count
    }

    private fun isDexMagic(bytes: ByteArray): Boolean =
        bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    private fun readUleb128(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var index = offset
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            index++
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result to index
            shift += 7
            if (shift > 28) return null
        }
        return null
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private const val HEADER_SIZE = 0x70
    private const val CHECKSUM_OFFSET = 8
    private const val SIGNATURE_OFFSET = 12
    private const val SIGNATURE_SIZE = 20

    private const val HASH_OFFSET = 32
    private const val FILE_SIZE_OFFSET = 32
    private const val STRING_IDS_SIZE_OFFSET = 56
    private const val STRING_IDS_OFFSET = 60

    private const val MAX_NAME_ATTEMPTS = 4096

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val LOWER_TAIL = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val UPPER_TAIL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
