package io.xpatch.patch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlEditor(private val source: ByteArray) {

    private val buf = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)

    private data class Attribute(val offset: Int)

    private data class Element(val start: Int, val headerSize: Int, val attrStart: Int, val attrCount: Int)

    private data class AttrSpec(val nameIndex: Int, val valueStringIndex: Int)

    fun readApplicationAttribute(attrName: String): String? =
        readAttribute("application", attrName)

    fun readAttribute(elementName: String, attrName: String): String? {
        val pool = StringPool.parse(source)
        val node = findElement(pool, elementName) ?: return null
        val attr = findAttribute(pool, node, attrName) ?: return null
        val index = u32(attr.offset + 16)
        return if (index in 0 until pool.count) pool.strings[index] else null
    }

    fun setApplicationAttribute(attrName: String, value: String): ByteArray =
        setStringAttribute("application", attrName, value)

    fun setStringAttribute(elementName: String, attrName: String, value: String): ByteArray {
        val pool = StringPool.parse(source)
        val element = findElement(pool, elementName) ?: error("manifest 中没有 <$elementName>")
        val attr = findAttribute(pool, element, attrName)

        val valueIndex = pool.strings.indexOf(value).takeIf { it >= 0 } ?: pool.append(value)

        return if (attr != null) {
            val poolBytes = pool.build()
            val bodyStart = 8 + pool.chunkSize
            val body = source.copyOfRange(bodyStart, source.size)
            val offsetInBody = attr.offset - bodyStart

            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(offsetInBody + 8, valueIndex)
            bb.put(offsetInBody + 14, 0)
            bb.put(offsetInBody + 15, TYPE_STRING.toByte())
            bb.putInt(offsetInBody + 16, valueIndex)
            assemble(poolBytes, body)
        } else {
            val nameIndex = pool.strings.indexOf(attrName).takeIf { it >= 0 }
                ?: pool.append(attrName)
            injectAttribute(
                pool,
                elementName,
                element,
                AttrSpec(nameIndex, valueIndex),
                TYPE_STRING,
                valueIndex,
                pool.strings.indexOf(ANDROID_NS).takeIf { it >= 0 } ?: pool.append(ANDROID_NS),
            )
        }
    }

    fun setIntAttribute(elementName: String, attrName: String, value: Int): ByteArray {
        val pool = StringPool.parse(source)
        val element = findElement(pool, elementName) ?: error("manifest 中没有 <$elementName>")
        val attr = findAttribute(pool, element, attrName)

        if (attr == null) {
            val nameIndex = pool.strings.indexOf(attrName).takeIf { it >= 0 }
                ?: pool.append(attrName)
            return injectAttribute(
                pool,
                elementName,
                element,
                AttrSpec(nameIndex, -1),
                TYPE_INT_DEC,
                value,
                pool.strings.indexOf(ANDROID_NS).takeIf { it >= 0 } ?: pool.append(ANDROID_NS),
            )
        }

        val bodyStart = 8 + pool.chunkSize
        val body = source.copyOfRange(bodyStart, source.size)
        val offsetInBody = attr.offset - bodyStart

        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(offsetInBody + 8, -1)
        bb.put(offsetInBody + 14, 0)
        bb.put(offsetInBody + 15, TYPE_INT_DEC.toByte())
        bb.putInt(offsetInBody + 16, value)

        return assemble(pool.build(), body)
    }

    private fun injectAttribute(
        pool: StringPool,
        elementName: String,
        element: Element,
        spec: AttrSpec,
        type: Int,
        data: Int,
        androidNsIndex: Int,
    ): ByteArray {
        val poolBytes = pool.build()
        val headerEnd = element.start + element.headerSize
        val attrStart = headerEnd + element.attrStart
        val attrCount = element.attrCount

        val bodyStart = 8 + pool.chunkSize
        val body = source.copyOfRange(bodyStart, source.size)
        val attrStartInBody = attrStart - bodyStart

        val inserted = ByteBuffer.allocate(ATTRIBUTE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        inserted.putInt(0, androidNsIndex)
        inserted.putInt(4, spec.nameIndex)
        inserted.putInt(8, spec.valueStringIndex)
        inserted.putShort(12, ATTRIBUTE_SIZE.toShort())
        inserted.put(14, 0)
        inserted.put(15, type.toByte())
        inserted.putInt(16, data)
        val insertBytes = inserted.array()

        val out = ByteArrayOutputStream(body.size + insertBytes.size)
        out.write(body, 0, attrStartInBody + attrCount * ATTRIBUTE_SIZE)
        out.write(insertBytes)
        out.write(
            body,
            attrStartInBody + attrCount * ATTRIBUTE_SIZE,
            body.size - (attrStartInBody + attrCount * ATTRIBUTE_SIZE),
        )
        val grown = out.toByteArray()

        val bb = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN)
        val elementInBody = element.start - bodyStart
        bb.putShort(elementInBody + 28, (attrCount + 1).toShort())
        bb.putInt(elementInBody + 4, i32In(body, elementInBody + 4) + insertBytes.size)

        val result = ByteArrayOutputStream()
        result.write(source, 0, 8)
        result.write(poolBytes)
        result.write(grown)
        val merged = result.toByteArray()
        putInt(merged, 4, merged.size)
        return merged
    }

    private fun i32In(body: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    private fun assemble(poolBytes: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(source, 0, 8)
        out.write(poolBytes)
        out.write(body)
        val result = out.toByteArray()
        putInt(result, 4, result.size)
        return result
    }

    private fun findElement(pool: StringPool, elementName: String): Element? {
        var pos = 8 + pool.chunkSize
        while (pos < source.size) {
            val type = u16(pos)
            val size = i32(pos + 4)
            if (type == CHUNK_START_ELEMENT) {
                val nameIndex = u32(pos + 20)
                if (nameIndex in 0 until pool.count && pool.strings[nameIndex] == elementName) {
                    val headerSize = u16(pos + 2)
                    return Element(
                        start = pos,
                        headerSize = headerSize,
                        attrStart = u16(pos + 24),
                        attrCount = u16(pos + 28),
                    )
                }
            }
            if (size <= 0) break
            pos += size
        }
        return null
    }

    private fun findAttribute(pool: StringPool, element: Element, attrName: String): Attribute? {
        val attrStart = element.start + element.headerSize + element.attrStart
        for (i in 0 until element.attrCount) {
            val offset = attrStart + i * ATTRIBUTE_SIZE
            val nameIndex = u32(offset + 4)
            if (nameIndex in 0 until pool.count && pool.strings[nameIndex] == attrName) {
                return Attribute(offset)
            }
        }
        return null
    }

    private fun u16(offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF

    private fun u32(offset: Int): Int = buf.getInt(offset)

    private fun i32(offset: Int): Int = buf.getInt(offset)

    private companion object {
        private const val CHUNK_AXML_FILE = 0x0003
        private const val CHUNK_STRING_POOL = 0x0001
        private const val CHUNK_START_ELEMENT = 0x0102
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val ATTRIBUTE_SIZE = 20
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        private fun putInt(array: ByteArray, offset: Int, value: Int) {
            array[offset] = (value and 0xFF).toByte()
            array[offset + 1] = ((value shr 8) and 0xFF).toByte()
            array[offset + 2] = ((value shr 16) and 0xFF).toByte()
            array[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }

    class StringPool private constructor(
        private val utf8: Boolean,
        val strings: MutableList<String>,
        val chunkSize: Int,
        private val styleData: ByteArray,
        private val styleOffsets: IntArray,
    ) {

        val count: Int get() = strings.size

        fun indexOf(value: String): Int = strings.indexOf(value)

        fun append(value: String): Int {
            strings.add(value)
            return strings.size - 1
        }

        fun build(): ByteArray {
            val encoded = strings.map { encode(it) }
            val offsets = IntArray(encoded.size)
            val data = ByteArrayOutputStream()
            encoded.forEachIndexed { index, bytes ->
                offsets[index] = data.size()
                data.write(bytes)
            }
            val rawData = data.toByteArray()
            val paddedSize = (rawData.size + 3) / 4 * 4
            val dataBytes = rawData.copyOf(paddedSize)
            val headerSize = 28
            val stringsStart = headerSize + 4 * strings.size + 4 * styleOffsets.size
            val total = stringsStart + dataBytes.size + styleData.size

            val out = ByteArray(total)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(0, CHUNK_STRING_POOL.toShort())
            bb.putShort(2, headerSize.toShort())
            bb.putInt(4, total)
            bb.putInt(8, strings.size)
            bb.putInt(12, styleOffsets.size)
            bb.putInt(16, if (utf8) 0x100 else 0)
            bb.putInt(20, stringsStart)
            bb.putInt(24, if (styleOffsets.isEmpty()) 0 else stringsStart + dataBytes.size)
            var p = headerSize
            offsets.forEach { bb.putInt(p, it); p += 4 }
            styleOffsets.forEach { bb.putInt(p, it); p += 4 }
            System.arraycopy(dataBytes, 0, out, stringsStart, dataBytes.size)
            if (styleData.isNotEmpty()) {
                System.arraycopy(styleData, 0, out, stringsStart + dataBytes.size, styleData.size)
            }
            return out
        }

        private fun encode(value: String): ByteArray {
            val out = ByteArrayOutputStream()
            if (utf8) {
                val body = value.toByteArray(Charsets.UTF_8)
                writeUtf8Length(out, value.length)
                writeUtf8Length(out, body.size)
                out.write(body)
                out.write(0)
            } else {
                val body = value.toByteArray(Charsets.UTF_16LE)
                out.write(value.length and 0xFF)
                out.write((value.length shr 8) and 0xFF)
                out.write(body)
                out.write(0)
                out.write(0)
            }
            return out.toByteArray()
        }

        private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
            if (length > 0x7F) {
                out.write(((length shr 8) and 0x7F) or 0x80)
            }
            out.write(length and 0xFF)
        }

        companion object {
            fun parse(source: ByteArray): StringPool {
                val bb = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
                val pos = 8
                require(bb.getShort(pos).toInt() and 0xFFFF == CHUNK_STRING_POOL) { "缺少字符串池" }
                val headerSize = bb.getShort(pos + 2).toInt() and 0xFFFF
                val chunkSize = bb.getInt(pos + 4)
                val count = bb.getInt(pos + 8)
                val styleCount = bb.getInt(pos + 12)
                val flags = bb.getInt(pos + 16)
                val stringsStart = bb.getInt(pos + 20)
                val stylesStart = bb.getInt(pos + 24)
                val utf8 = (flags and 0x100) != 0

                val offsets = IntArray(count)
                for (i in 0 until count) {
                    offsets[i] = bb.getInt(pos + headerSize + i * 4)
                }
                val styleOffsets = IntArray(styleCount)
                for (i in 0 until styleCount) {
                    styleOffsets[i] = bb.getInt(pos + headerSize + count * 4 + i * 4)
                }

                val strings = ArrayList<String>(count)
                for (i in 0 until count) {
                    strings.add(decode(source, pos + stringsStart + offsets[i], utf8))
                }

                val styleData = if (stylesStart > 0 && pos + stylesStart < source.size) {
                    source.copyOfRange(pos + stylesStart, pos + chunkSize)
                } else {
                    ByteArray(0)
                }

                return StringPool(utf8, strings, chunkSize, styleData, styleOffsets)
            }

            private fun decode(data: ByteArray, offset: Int, utf8: Boolean): String {
                if (utf8) {
                    var p = offset
                    var len = data[p].toInt() and 0xFF
                    if (len and 0x80 != 0) {
                        len = ((len and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                    } else {
                        p += 1
                    }
                    var byteLen = data[p].toInt() and 0xFF
                    if (byteLen and 0x80 != 0) {
                        byteLen = ((byteLen and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)
                        p += 2
                    } else {
                        p += 1
                    }
                    return String(data, p, byteLen, Charsets.UTF_8)
                }
                var p = offset
                var len = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                if (len and 0x8000 != 0) {
                    len = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                    p += 2
                }
                return String(data, p, len * 2, Charsets.UTF_16LE)
            }
        }
    }
}
