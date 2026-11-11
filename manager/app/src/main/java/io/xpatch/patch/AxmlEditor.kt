package io.xpatch.patch

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlEditor(private val source: ByteArray) {

    private val buf = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)

    private data class Attribute(val offset: Int)

    private data class Element(
        val start: Int,
        val headerSize: Int,
        val attrStart: Int,
        val attrCount: Int,
        val attrSize: Int,
    )

    private data class AttrSpec(val nameIndex: Int, val valueStringIndex: Int)

    private data class ProviderNode(
        val element: Element,
        val name: String?,
        val authorities: String?,
        val exported: Int?,
        val exportedRaw: Int?,
        val exportedType: Int?,
    )

    private data class ProviderScan(val sameName: ProviderNode?, val sameAuthority: ProviderNode?)

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

    fun addProvider(className: String, authority: String): ByteArray {
        val scan = scanProviders(className, authority)
        scan.sameName?.let { node ->
            val exportedHealthy = (node.exported ?: 0) == 0 &&
                (node.exportedRaw == null || node.exportedRaw == RAW_VALUE_NONE) &&
                (node.exportedType == null || node.exportedType == TYPE_INT_BOOLEAN)
            val healthy = u32(node.element.start + 16) == ELEMENT_NS_NONE &&
                i32(node.element.start + 8) != 0 &&
                node.authorities == authority &&
                exportedHealthy
            if (healthy) return source
            return reuseProvider(node, authority)
        }
        if (scan.sameAuthority != null) return source
        return insertProvider(className, authority)
    }

    fun hasProvider(className: String, authority: String): Boolean {
        val scan = scanProviders(className, authority)
        return scan.sameName != null || scan.sameAuthority != null
    }

    fun addUsesPermission(permission: String): ByteArray {
        if (hasUsesPermission(permission)) return source
        return insertUsesPermission(permission)
    }

    fun hasUsesPermission(permission: String): Boolean {
        val pool = StringPool.parse(source)
        val manifest = findElement(pool, "manifest") ?: return false
        var found = false
        eachChildElement(manifest) { start ->
            if (found) return@eachChildElement
            val nameIndex = u32(start + 20)
            if (nameIndex in 0 until pool.count && pool.strings[nameIndex] == "uses-permission") {
                if (stringAttribute(pool, elementAt(start), ATTR_NAME) == permission) found = true
            }
        }
        return found
    }

    private fun insertProvider(className: String, authority: String): ByteArray {
        val pool = StringPool.parse(source)
        val application = findElement(pool, "application") ?: error("manifest 中没有 <application>")
        val androidNs = stringIndex(pool, ANDROID_NS)
        val provider = stringIndex(pool, "provider")
        val nameAttr = stringIndex(pool, ATTR_NAME)
        val authoritiesAttr = stringIndex(pool, ATTR_AUTHORITIES)
        val exportedAttr = stringIndex(pool, ATTR_EXPORTED)
        val classIndex = stringIndex(pool, className)
        val authorityIndex = stringIndex(pool, authority)

        val oldBodyStart = 8 + pool.chunkSize
        val hasMap = u16(oldBodyStart) == CHUNK_RESOURCE_MAP
        val oldMapSize = if (hasMap) i32(oldBodyStart + 4) else 0
        val oldIds = ArrayList<Int>()
        if (hasMap) {
            var pos = oldBodyStart + 8
            while (pos < oldBodyStart + oldMapSize) {
                oldIds.add(i32(pos))
                pos += 4
            }
        }
        val newPool = pool.build()
        val mapCount = maxOf(oldIds.size, pool.count)
        val ids = IntArray(mapCount)
        oldIds.forEachIndexed { index, value -> if (index < ids.size) ids[index] = value }
        ids[nameAttr] = RES_ID_NAME
        ids[authoritiesAttr] = RES_ID_AUTHORITIES
        ids[exportedAttr] = RES_ID_EXPORTED

        val map = ByteBuffer.allocate(8 + ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        map.putShort(0, CHUNK_RESOURCE_MAP.toShort())
        map.putShort(2, 8)
        map.putInt(4, 8 + ids.size * 4)
        ids.forEachIndexed { index, value -> map.putInt(8 + index * 4, value) }

        val restStart = oldBodyStart + oldMapSize
        val rest = source.copyOfRange(restStart, source.size)
        val body = ByteArray(map.capacity() + rest.size)
        System.arraycopy(map.array(), 0, body, 0, map.capacity())
        System.arraycopy(rest, 0, body, map.capacity(), rest.size)

        val appOffset = application.start - restStart + map.capacity()
        require(application.attrSize == ATTRIBUTE_SIZE) { "不支持的属性布局（attributeSize=${application.attrSize}）" }
        val insertAt = appOffset + application.headerSize + application.attrStart +
            application.attrCount * application.attrSize
        val attrCount = 3
        val startSize = 36 + attrCount * ATTRIBUTE_SIZE
        val start = ByteBuffer.allocate(startSize).order(ByteOrder.LITTLE_ENDIAN)
        start.putShort(0, CHUNK_START_ELEMENT.toShort())
        start.putShort(2, 16)
        start.putInt(4, startSize)
        start.putInt(8, 1)
        start.putInt(12, -1)
        start.putInt(16, -1)
        start.putInt(20, provider)
        start.putShort(24, 20)
        start.putShort(26, ATTRIBUTE_SIZE.toShort())
        start.putShort(28, attrCount.toShort())
        start.putShort(30, 0)
        start.putShort(32, 0)
        start.putShort(34, 0)
        putStringAttribute(start, 36, androidNs, nameAttr, classIndex)
        putStringAttribute(start, 56, androidNs, authoritiesAttr, authorityIndex)
        putIntAttribute(start, 76, androidNs, exportedAttr, RAW_VALUE_NONE, 0, TYPE_INT_BOOLEAN)

        val end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        end.putShort(0, CHUNK_END_ELEMENT.toShort())
        end.putShort(2, 16)
        end.putInt(4, 24)
        end.putInt(8, 0)
        end.putInt(12, -1)
        end.putInt(16, -1)
        end.putInt(20, provider)

        val result = ByteArray(8 + newPool.size + body.size + startSize + 24)
        System.arraycopy(source, 0, result, 0, 8)
        System.arraycopy(newPool, 0, result, 8, newPool.size)
        System.arraycopy(body, 0, result, 8 + newPool.size, insertAt)
        System.arraycopy(start.array(), 0, result, 8 + newPool.size + insertAt, startSize)
        System.arraycopy(end.array(), 0, result, 8 + newPool.size + insertAt + startSize, 24)
        System.arraycopy(body, insertAt, result, 8 + newPool.size + insertAt + startSize + 24, body.size - insertAt)
        putInt(result, 4, result.size)
        return result
    }

    private fun insertUsesPermission(permission: String): ByteArray {
        val pool = StringPool.parse(source)
        val application = findElement(pool, "application") ?: error("manifest 中没有 <application>")
        val androidNs = stringIndex(pool, ANDROID_NS)
        val usesPermission = stringIndex(pool, "uses-permission")
        val nameAttr = stringIndex(pool, ATTR_NAME)
        val permissionIndex = stringIndex(pool, permission)

        val oldBodyStart = 8 + pool.chunkSize
        val hasMap = u16(oldBodyStart) == CHUNK_RESOURCE_MAP
        val oldMapSize = if (hasMap) i32(oldBodyStart + 4) else 0
        val oldIds = ArrayList<Int>()
        if (hasMap) {
            var pos = oldBodyStart + 8
            while (pos < oldBodyStart + oldMapSize) {
                oldIds.add(i32(pos))
                pos += 4
            }
        }
        val newPool = pool.build()
        val mapCount = maxOf(oldIds.size, pool.count)
        val ids = IntArray(mapCount)
        oldIds.forEachIndexed { index, value -> if (index < ids.size) ids[index] = value }
        ids[nameAttr] = RES_ID_NAME

        val map = ByteBuffer.allocate(8 + ids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        map.putShort(0, CHUNK_RESOURCE_MAP.toShort())
        map.putShort(2, 8)
        map.putInt(4, 8 + ids.size * 4)
        ids.forEachIndexed { index, value -> map.putInt(8 + index * 4, value) }

        val restStart = oldBodyStart + oldMapSize
        val rest = source.copyOfRange(restStart, source.size)
        val body = ByteArray(map.capacity() + rest.size)
        System.arraycopy(map.array(), 0, body, 0, map.capacity())
        System.arraycopy(rest, 0, body, map.capacity(), rest.size)

        val appOffset = application.start - restStart + map.capacity()
        val insertAt = appOffset
        val attrCount = 1
        val startSize = 36 + attrCount * ATTRIBUTE_SIZE
        val start = ByteBuffer.allocate(startSize).order(ByteOrder.LITTLE_ENDIAN)
        start.putShort(0, CHUNK_START_ELEMENT.toShort())
        start.putShort(2, 16)
        start.putInt(4, startSize)
        start.putInt(8, 1)
        start.putInt(12, -1)
        start.putInt(16, ELEMENT_NS_NONE)
        start.putInt(20, usesPermission)
        start.putShort(24, 20)
        start.putShort(26, ATTRIBUTE_SIZE.toShort())
        start.putShort(28, attrCount.toShort())
        start.putShort(30, 0)
        start.putShort(32, 0)
        start.putShort(34, 0)
        putStringAttribute(start, 36, androidNs, nameAttr, permissionIndex, RAW_VALUE_NONE)

        val end = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        end.putShort(0, CHUNK_END_ELEMENT.toShort())
        end.putShort(2, 16)
        end.putInt(4, 24)
        end.putInt(8, 0)
        end.putInt(12, -1)
        end.putInt(16, -1)
        end.putInt(20, usesPermission)

        val result = ByteArray(8 + newPool.size + body.size + startSize + 24)
        System.arraycopy(source, 0, result, 0, 8)
        System.arraycopy(newPool, 0, result, 8, newPool.size)
        System.arraycopy(body, 0, result, 8 + newPool.size, insertAt)
        System.arraycopy(start.array(), 0, result, 8 + newPool.size + insertAt, startSize)
        System.arraycopy(end.array(), 0, result, 8 + newPool.size + insertAt + startSize, 24)
        System.arraycopy(body, insertAt, result, 8 + newPool.size + insertAt + startSize + 24, body.size - insertAt)
        putInt(result, 4, result.size)
        return result
    }

    private fun reuseProvider(node: ProviderNode, authority: String): ByteArray {
        val pool = StringPool.parse(source)
        val element = node.element
        val authoritiesAttr = findAttribute(pool, element, ATTR_AUTHORITIES)
        val exportedAttr = findAttribute(pool, element, ATTR_EXPORTED)
        val authorityIndex = stringIndex(pool, authority)
        val appendAuthorities = authoritiesAttr == null
        val androidNs = if (appendAuthorities) stringIndex(pool, ANDROID_NS) else -1
        val authoritiesName = if (appendAuthorities) stringIndex(pool, ATTR_AUTHORITIES) else -1
        val newPool = pool.build()

        val bodyStart = 8 + pool.chunkSize
        val body = source.copyOfRange(bodyStart, source.size)
        val elementInBody = element.start - bodyStart
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)

        if (u32(element.start + 16) != ELEMENT_NS_NONE) bb.putInt(elementInBody + 16, ELEMENT_NS_NONE)
        if (i32(element.start + 8) == 0) bb.putInt(elementInBody + 8, 1)
        exportedAttr?.let { attr ->
            val at = attr.offset - bodyStart
            val dirty = bb.getInt(at + 16) != 0 ||
                bb.getInt(at + 8) != RAW_VALUE_NONE ||
                (body[at + 15].toInt() and 0xFF) != TYPE_INT_BOOLEAN
            if (dirty) {
                bb.putInt(at + 8, RAW_VALUE_NONE)
                bb.put(at + 14, 0)
                bb.put(at + 15, TYPE_INT_BOOLEAN.toByte())
                bb.putInt(at + 16, 0)
            }
        }
        if (!appendAuthorities) {
            val at = requireNotNull(authoritiesAttr).offset - bodyStart
            bb.putInt(at + 8, authorityIndex)
            bb.put(at + 14, 0)
            bb.put(at + 15, TYPE_STRING.toByte())
            bb.putInt(at + 16, authorityIndex)
        }
        val grown = if (!appendAuthorities) {
            body
        } else {
            appendAttribute(
                body = body,
                elementInBody = elementInBody,
                element = element,
                ns = androidNs,
                name = authoritiesName,
                valueIndex = authorityIndex,
                type = TYPE_STRING,
            )
        }
        return assemble(newPool, grown)
    }

    private fun appendAttribute(
        body: ByteArray,
        elementInBody: Int,
        element: Element,
        ns: Int,
        name: Int,
        valueIndex: Int,
        type: Int,
    ): ByteArray {
        require(element.attrSize == ATTRIBUTE_SIZE) { "不支持的属性布局（attributeSize=${element.attrSize}）" }
        val insertAt = elementInBody + element.headerSize + element.attrStart +
            element.attrCount * element.attrSize
        val extra = ByteBuffer.allocate(ATTRIBUTE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        extra.putInt(0, ns)
        extra.putInt(4, name)
        extra.putInt(8, valueIndex)
        extra.putShort(12, ATTRIBUTE_SIZE.toShort())
        extra.put(14, 0)
        extra.put(15, type.toByte())
        extra.putInt(16, valueIndex)

        val grown = ByteArray(body.size + ATTRIBUTE_SIZE)
        System.arraycopy(body, 0, grown, 0, insertAt)
        System.arraycopy(extra.array(), 0, grown, insertAt, ATTRIBUTE_SIZE)
        System.arraycopy(body, insertAt, grown, insertAt + ATTRIBUTE_SIZE, body.size - insertAt)

        val bb = ByteBuffer.wrap(grown).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(elementInBody + 28, (element.attrCount + 1).toShort())
        bb.putInt(elementInBody + 4, i32In(body, elementInBody + 4) + ATTRIBUTE_SIZE)
        return grown
    }

    private fun scanProviders(className: String, authority: String): ProviderScan {
        val pool = StringPool.parse(source)
        val application = findElement(pool, "application") ?: return ProviderScan(null, null)
        var sameName: ProviderNode? = null
        var sameAuthority: ProviderNode? = null
        eachChildElement(application) { start ->
            val nameIndex = u32(start + 20)
            if (nameIndex in 0 until pool.count && pool.strings[nameIndex] == "provider") {
                val node = providerNode(pool, start)
                if (sameName == null && node.name == className) sameName = node
                if (sameAuthority == null && node.authorities == authority) sameAuthority = node
            }
        }
        return ProviderScan(sameName, sameAuthority)
    }

    private fun eachChildElement(parent: Element, action: (Int) -> Unit) {
        var pos = parent.start
        var depth = 0
        while (pos + 8 <= source.size) {
            val type = u16(pos)
            val size = i32(pos + 4)
            if (size < 8 || pos + size > source.size) break
            when (type) {
                CHUNK_START_ELEMENT -> {
                    if (depth == 1) action(pos)
                    depth++
                }

                CHUNK_END_ELEMENT -> {
                    depth--
                    if (depth <= 0) break
                }
            }
            pos += size
        }
    }

    private fun providerNode(pool: StringPool, start: Int): ProviderNode {
        val element = elementAt(start)
        val exportedAttr = findAttribute(pool, element, ATTR_EXPORTED)
        return ProviderNode(
            element = element,
            name = stringAttribute(pool, element, ATTR_NAME),
            authorities = stringAttribute(pool, element, ATTR_AUTHORITIES),
            exported = intAttribute(pool, element, ATTR_EXPORTED),
            exportedRaw = exportedAttr?.let { u32(it.offset + 8) },
            exportedType = exportedAttr?.let { source[it.offset + 15].toInt() and 0xFF },
        )
    }

    private fun stringAttribute(pool: StringPool, element: Element, attrName: String): String? {
        val attr = findAttribute(pool, element, attrName) ?: return null
        if (attr.offset + ATTRIBUTE_SIZE > source.size) return null
        if ((source[attr.offset + 15].toInt() and 0xFF) != TYPE_STRING) return null
        val index = u32(attr.offset + 16)
        return if (index in 0 until pool.count) pool.strings[index] else null
    }

    private fun intAttribute(pool: StringPool, element: Element, attrName: String): Int? {
        val attr = findAttribute(pool, element, attrName) ?: return null
        if (attr.offset + ATTRIBUTE_SIZE > source.size) return null
        val type = source[attr.offset + 15].toInt() and 0xFF
        return if (type in TYPE_FIRST_INT..TYPE_LAST_INT) u32(attr.offset + 16) else null
    }

    private fun elementAt(start: Int): Element = Element(
        start = start,
        headerSize = u16(start + 2),
        attrStart = u16(start + 24),
        attrCount = u16(start + 28),
        attrSize = u16(start + 26),
    )

    private fun stringIndex(pool: StringPool, value: String): Int =
        pool.strings.indexOf(value).takeIf { it >= 0 } ?: pool.append(value)

    private fun putStringAttribute(
        buffer: ByteBuffer,
        offset: Int,
        ns: Int,
        name: Int,
        value: Int,
        raw: Int = value,
    ) {
        buffer.putInt(offset, ns)
        buffer.putInt(offset + 4, name)
        buffer.putInt(offset + 8, raw)
        buffer.putShort(offset + 12, ATTRIBUTE_SIZE.toShort())
        buffer.put(offset + 14, 0)
        buffer.put(offset + 15, TYPE_STRING.toByte())
        buffer.putInt(offset + 16, value)
    }

    private fun putIntAttribute(
        buffer: ByteBuffer,
        offset: Int,
        ns: Int,
        name: Int,
        raw: Int,
        value: Int,
        type: Int = TYPE_INT_DEC,
    ) {
        buffer.putInt(offset, ns)
        buffer.putInt(offset + 4, name)
        buffer.putInt(offset + 8, raw)
        buffer.putShort(offset + 12, ATTRIBUTE_SIZE.toShort())
        buffer.put(offset + 14, 0)
        buffer.put(offset + 15, type.toByte())
        buffer.putInt(offset + 16, value)
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
        require(element.attrSize == ATTRIBUTE_SIZE) { "不支持的属性布局（attributeSize=${element.attrSize}）" }
        val poolBytes = pool.build()
        val headerEnd = element.start + element.headerSize
        val attrStart = headerEnd + element.attrStart
        val attrCount = element.attrCount
        val stride = element.attrSize

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
        out.write(body, 0, attrStartInBody + attrCount * stride)
        out.write(insertBytes)
        out.write(
            body,
            attrStartInBody + attrCount * stride,
            body.size - (attrStartInBody + attrCount * stride),
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
                    return elementAt(pos)
                }
            }
            if (size <= 0) break
            pos += size
        }
        return null
    }

    private fun findAttribute(pool: StringPool, element: Element, attrName: String): Attribute? {
        val attrStart = element.start + element.headerSize + element.attrStart
        val stride = if (element.attrSize > 0) element.attrSize else ATTRIBUTE_SIZE
        for (i in 0 until element.attrCount) {
            val offset = attrStart + i * stride
            if (offset < 0 || offset + stride > source.size) break
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
        private const val CHUNK_END_ELEMENT = 0x0103
        private const val CHUNK_RESOURCE_MAP = 0x0180
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_DEC = 0x10
        private const val TYPE_FIRST_INT = 0x10
        private const val TYPE_LAST_INT = 0x1f
        private const val TYPE_INT_BOOLEAN = 0x12
        private const val ATTRIBUTE_SIZE = 20

        private const val RAW_VALUE_NONE = -1

        private const val ELEMENT_NS_NONE = -1
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val ATTR_NAME = "name"
        private const val ATTR_AUTHORITIES = "authorities"
        private const val ATTR_EXPORTED = "exported"

        private const val RES_ID_NAME = 0x01010003
        private const val RES_ID_EXPORTED = 0x01010010
        private const val RES_ID_AUTHORITIES = 0x01010018

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
