package io.xpatch.patch.obfuscate

import java.security.MessageDigest
import java.util.Random
import java.util.zip.Adler32

/**
 * 引导桩 dex 的"就地等长改名"（docs/inject-path-obfuscation.md §4.3）。
 *
 * 为什么只能等长：dex 的 `string_ids` 必须按 **UTF-16 码元序**有序，ART 加载后靠二分查类描述符；
 * 换长度就得重排整张表并重写所有引用它的 u4 索引（class_def / type_id / code 里的 const-string），
 * 那是"重写 dex"而不是"改个名字"。所以这里只做一件事：在有序窗口 `(prev, next)` 内换一个等长名字。
 *
 * 前置条件（任一不满足 → 返回 `renamed = false` + 原始字节，调用方照常写路径混淆、只是类名不变）：
 * 1. 两个描述符在整份 dex 字节里各**恰好出现 1 次**（多一处说明还有我们没覆盖的引用点，
 *    改一处就是"半改"的 dex，ART 会拒绝）；
 * 2. 字符串表按 UTF-16 码元序有序（用 [String.compareTo]，**不是** UTF-8 字节序：桩里有中文日志串，
 *    两种序在非 ASCII 段并不等价）；
 * 3. 能找到有序窗口，且改名后整张表仍有序、整份 dex 不再出现 `io/xpatch/stub/`。
 *
 * 绝不产出可能被 ART 拒绝的 dex：宁可让这一层降级（日志说明原因），也不能让产物"装得上打不开"。
 */
object StubRewriter {

    const val DEFAULT_FACTORY = "io.xpatch.stub.AppEntry"
    const val DEFAULT_PROVIDER = "io.xpatch.stub.XpProvider"

    private const val FACTORY_INTERNAL = "io/xpatch/stub/AppEntry"
    private const val PROVIDER_INTERNAL = "io/xpatch/stub/XpProvider"

    /** 改名前的公共前缀：改名后整份 dex 里不能再出现它（否则等于没改成）。 */
    private val STUB_PREFIX = "io/xpatch/stub/".toByteArray(Charsets.US_ASCII)

    data class Result(
        val bytes: ByteArray,
        /** 点分类名，直接写进清单的 `appComponentFactory`。 */
        val factoryDashed: String,
        /** 点分类名，entryHook 的 `<provider android:name>` 用它。 */
        val providerDashed: String,
        val renamed: Boolean,
        /** 降级原因（`renamed == false` 时非空），调用方原样打进日志。 */
        val reason: String?,
    ) {
        // data class 持有 ByteArray 时 equals/hashCode 会退化成引用比较，误导调用方，这里显式改成内容比较。
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

    /** 显式不改名（开关关闭，或上层决定降级）：把原始字节和旧类名包一层，调用方不必分叉。 */
    fun skipped(dex: ByteArray, reason: String?): Result = Result(
        bytes = dex,
        factoryDashed = DEFAULT_FACTORY,
        providerDashed = DEFAULT_PROVIDER,
        renamed = false,
        reason = reason,
    )

    /**
     * @param dex 原始引导桩字节（不会被修改，改名结果在返回值的 `bytes` 里）
     * @param seed 只决定名字内容与重试顺序：同一个 dex + 同一个 seed 必然得到同一结果，便于复现问题
     * @param appEntryInternal / providerInternal 目标类内部名（不含 `L`/`;`）。默认值来自契约；
     *   改名逻辑本身与具体名字无关，长度和有序窗口全部从 dex 里现算。
     * @param preferredFactory / preferredProvider 点分旧类名。二次打包时输入清单里已经有上一版
     *   随机类名，能复用就复用——否则换了姓，清单里会留下一个指向**不存在类**的 `<provider>`
     *   （PackageManager 实例化失败 → 目标应用启动即崩）。复用同样要过下面全部校验。
     */
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
            // 声明体积 != 实际体积：尾部还有别的数据（有的加固包会拼尾），就地改会把校验和算错。
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
        // 前置条件 1：整份 dex 字节里各恰好出现一次。用字节扫描而不是只数字符串表，
        // 因为注解、调试信息、第二个字符串副本都可能是额外的引用点。
        val factoryHits = countOccurrences(dex, factoryDescriptor.toByteArray(Charsets.US_ASCII))
        if (factoryHits != 1) {
            return skipped(dex, "$appEntryInternal 在 dex 里出现 $factoryHits 次（需要恰好 1 次）")
        }
        val providerHits = countOccurrences(dex, providerDescriptor.toByteArray(Charsets.US_ASCII))
        if (providerHits != 1) {
            return skipped(dex, "$providerInternal 在 dex 里出现 $providerHits 次（需要恰好 1 次）")
        }

        val table = parseTable(dex, stringIdsSize, stringIdsOffset) ?: return skipped(dex, "字符串表结构损坏")
        // 前置条件 2：原表本身按 UTF-16 码元序有序，否则"插入后仍有序"这个判据不成立。
        if (!isSorted(table.texts)) return skipped(dex, "字符串表不是按 UTF-16 码元有序排列，无法安全等长替换")

        val factoryIndex = table.texts.indexOf(factoryDescriptor)
        if (factoryIndex < 0) return skipped(dex, "$appEntryInternal 不在字符串表里")
        val providerIndex = table.texts.indexOf(providerDescriptor)
        if (providerIndex < 0) return skipped(dex, "$providerInternal 不在字符串表里")
        // 就地替换要求"码元数 == 字节数"（纯 ASCII）；描述符是 ASCII，字节长度对不上就是异常数据。
        if (table.ends[factoryIndex] - table.starts[factoryIndex] != factoryDescriptor.length) {
            return skipped(dex, "$appEntryInternal 不是纯 ASCII 描述符")
        }
        if (table.ends[providerIndex] - table.starts[providerIndex] != providerDescriptor.length) {
            return skipped(dex, "$providerInternal 不是纯 ASCII 描述符")
        }

        // 有序窗口：新名字必须落在原位置的前后邻居之间，整张表才不用重排。
        val factoryPrev = if (factoryIndex > 0) table.texts[factoryIndex - 1] else null
        val factoryNext = if (factoryIndex + 1 < table.size) table.texts[factoryIndex + 1] else null
        val providerPrev = if (providerIndex > 0) table.texts[providerIndex - 1] else null
        val providerNext = if (providerIndex + 1 < table.size) table.texts[providerIndex + 1] else null
        if (factoryPrev == null && factoryNext == null) return skipped(dex, "描述符位于字符串表首尾，没有有序窗口")
        if (providerPrev == null && providerNext == null) return skipped(dex, "描述符位于字符串表首尾，没有有序窗口")

        val random = Random(seed)
        var attempt = 0
        // 先试"复用上一版类名"，失败再走随机；两条路都要过同一套校验。
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
            // 两个描述符在桩里相邻，互为邻居：它们之间的相对顺序也得守住。
            if (factoryIndex < providerIndex && patchedFactory.compareTo(patchedProvider) >= 0) continue
            if (providerIndex < factoryIndex && patchedProvider.compareTo(patchedFactory) >= 0) continue
            // 换完之后整张表仍有序？
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
            // 整份 dex 里不能再出现公共前缀（这就是"改名改干净了"的定义）。
            if (countOccurrences(candidate, STUB_PREFIX) != 0) continue
            // 新名字不能与已有字符串撞车：撞了就是两个类同一个描述符，类加载行为不确定。
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

    /**
     * 重算 dex header 的两个校验字段。
     *
     * 顺序不能反：`signature = SHA-1(bytes[32..])`，`checksum = Adler32(bytes[12..])`（含 signature）。
     * 这两个字段错了 ART 会直接拒绝 dex（`InMemoryDexClassLoader` 也校验），产物表现为"装得上打不开"。
     */
    private fun refreshHeader(dex: ByteArray) {
        val signature = MessageDigest.getInstance("SHA-1").apply {
            update(dex, HASH_OFFSET, dex.size - HASH_OFFSET)
        }.digest()
        System.arraycopy(signature, 0, dex, SIGNATURE_OFFSET, SIGNATURE_SIZE)
        val adler = Adler32().apply { update(dex, SIGNATURE_OFFSET, dex.size - SIGNATURE_OFFSET) }
        putU32(dex, CHECKSUM_OFFSET, adler.value)
    }

    /**
     * 生成"看起来像 R8 产物"的等长内部名：`[a-z][a-z0-9]*` 段 1~3 个 + 末段 `[A-Z][A-Za-z0-9]*`。
     *
     * 长度必须精确等于原值（调用方保证），所以先按段数把余量随机摊到各段上；每段首字符都是
     * 字母（段首是数字会被 dex verifier 挑刺）。
     *
     * [firstHint] 只是**采样偏好**，用来把首字符落在邻居之间的那一段上（桩里的窗口很窄，
     * 纯均匀采样要靠重试撞）。是不是真的合法由调用方按码元序逐条判定，这里不做任何假设。
     */
    private fun randomInnerName(random: Random, innerLength: Int, firstHint: IntRange?): String? {
        if (innerLength < 3) return null
        val segments = when {
            innerLength < 5 -> 1
            random.nextInt(10) < 1 -> 1
            random.nextBoolean() -> 2
            else -> 3
        }
        // 每段至少 1 字符，段间还要 1 个 '/'
        val chars = innerLength - (segments - 1)
        if (chars < segments) return null
        val lengths = IntArray(segments) { 1 }
        repeat(chars - segments) { lengths[random.nextInt(segments)]++ }
        val text = StringBuilder(innerLength)
        for (index in 0 until segments) {
            if (index > 0) text.append('/')
            val size = lengths[index]
            if (index == segments - 1) {
                // 末段首字母大写：R8 混淆后的类名几乎总是这样（顶层类首字母大写）。
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

    /**
     * 从有序窗口的上下界推出"首字符大概该落在哪一段"。
     *
     * 描述符都以 `L` 开头，所以两侧邻居的第 2 个字符天然框住了候选名首字符的范围：
     * 桩里下界是 `Ld…`、上界是 `Li…`/`Lv…`，采样落在 `d`~`v` 之间就能一次命中。
     * 只做偏好、不做判据；边界不含 `L` 或长度不足时返回 null（退回均匀采样）。
     */
    private fun firstCharHint(lower: String?, upper: String?): IntRange? {
        if (lower == null || upper == null || lower.length < 2 || upper.length < 2) return null
        if (!lower.startsWith("L") || !upper.startsWith("L")) return null
        val from = maxOf(lower[1].code, 'a'.code)
        val to = minOf(upper[1].code, 'z'.code)
        if (from > to) return null
        return from..to
    }

    /** 点分类名 → 内部名；长度对不上（说明它来自另一版桩）就返回 null，交给随机分支。 */
    private fun innerNameOf(dashed: String, innerLength: Int): String? {
        val internal = dashed.replace('.', '/')
        if (internal.length != innerLength) return null
        val legal = internal.all { char ->
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '/' || char == '_' || char == '$'
        }
        return if (legal) internal else null
    }

    /**
     * 读整张字符串表：每条只取「码元数 + MUTF-8 解码后的文本 + payload 字节区间」。
     *
     * dex 的 `string_data_item` 里 uleb128 是**UTF-16 码元数**，不是字节数；含多字节字符时两者不等。
     * 解码用的是 MUTF-8 而不是标准 UTF-8：`String(bytes, UTF_8)` 遇到代理对/中文常量会吐 U+FFFD，
     * 用那种字符串比大小得出的"有序"结论没有意义。
     */
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
            // 结尾位置以解码器实际消费的字节数为准，不用"字符 → 字节"反推：桩里有非规范编码
            // （源码按 GBK 编译时留下的字节会被当成 overlong MUTF-8），反推会算短、读错位置。
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

    /**
     * 按 MUTF-8 解码 `units` 个 UTF-16 码元，返回 (文本, 实际消费的字节数)。
     *
     * 为什么要返回字节数：桩里有非规范编码（见 [parseTable] 的注释），"字符 → 字节数"反推
     * 会算短，只有解码器自己知道它走了多少字节，用这个值定位 string_data 的 0 结尾才可靠。
     *
     * 只解码、不猜：任何非法序列都返回 null（调用方降级），绝不把半个字符当成"有序"的证据。
     * 4 字节序列按标准 UTF-8 的码点展开成代理对——MUTF-8 本应用两个 3 字节序列，
     * 但有些工具链直接写标准 UTF-8，两种都要认。
     */
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

    /** 字符串表视图：只保留下标 → 字节区间与解码文本，改名时等长覆盖。 */
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

    /** SHA-1 覆盖 `dex[32..]`：magic(8) + checksum(4) + signature(20) 之后的全部内容。 */
    private const val HASH_OFFSET = 32
    private const val FILE_SIZE_OFFSET = 32
    private const val STRING_IDS_SIZE_OFFSET = 56
    private const val STRING_IDS_OFFSET = 60

    /**
     * 重试预算：每次只是拼一个 25/27 字节的名字 + 两次 `compareTo`，失败代价可忽略。
     * 取 4096 是为了对"窗口很窄"的 dex 也留足余量；实测桩里几十次内就能命中。
     */
    private const val MAX_NAME_ATTEMPTS = 4096

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val LOWER_TAIL = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val UPPER_TAIL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
