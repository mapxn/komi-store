package zed.rainxch.core.data.packages

internal data class ManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
)

internal object AndroidManifestParser {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val NO_ENTRY = -1
    private const val MAX_STRINGS = 200_000
    private const val ELEMENT_BODY_OFFSET = 16
    private const val MIN_ATTRIBUTE_SIZE = 20

    fun parse(bytes: ByteArray): ManifestInfo? =
        try {
            parseOrThrow(bytes)
        } catch (_: Exception) {
            null
        }

    private fun parseOrThrow(b: ByteArray): ManifestInfo? {
        if (b.size < 8 || u16(b, 0) != RES_XML_TYPE) return null

        val declaredSize = u32(b, 4).toLong() and 0xFFFFFFFFL
        val end = minOf(declaredSize, b.size.toLong()).toInt()
        var position = u16(b, 2)
        var strings: List<String> = emptyList()
        var manifest: Map<String, Any?>? = null
        var application: Map<String, Any?>? = null

        while (position + 8 <= end) {
            val type = u16(b, position)
            val size = u32(b, position + 4)
            if (size < 8 || size > end - position) break

            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(b, position)
                RES_XML_START_ELEMENT_TYPE -> {
                    val (name, attributes) = readElement(b, position, strings)
                    when (name) {
                        "manifest" -> if (manifest == null) manifest = attributes
                        "application" -> if (application == null) application = attributes
                    }
                }
            }
            position += size
        }

        return toInfo(manifest ?: return null, application)
    }

    private fun toInfo(
        manifest: Map<String, Any?>,
        application: Map<String, Any?>?,
    ): ManifestInfo? {
        val packageName = (manifest["package"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val minor = (manifest["versionCode"] as? Long) ?: 0L
        val major = (manifest["versionCodeMajor"] as? Long) ?: 0L
        return ManifestInfo(
            packageName = packageName,
            versionName = manifest["versionName"] as? String,
            versionCode = (major shl 32) or minor,
            label = application?.get("label") as? String,
        )
    }

    private fun readStringPool(
        b: ByteArray,
        chunkStart: Int,
    ): List<String> {
        val headerSize = u16(b, chunkStart + 2)
        val count = u32(b, chunkStart + 8)
        val flags = u32(b, chunkStart + 16)
        val stringsStart = u32(b, chunkStart + 20)
        require(count in 0..MAX_STRINGS)

        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsStart = chunkStart + headerSize
        return List(count) { index ->
            var offset = chunkStart + stringsStart + u32(b, offsetsStart + 4 * index)
            if (utf8) {
                val firstLengthByte = u8(b, offset)
                offset += if ((firstLengthByte and 0x80) != 0) 2 else 1
                var byteLength = u8(b, offset)
                offset += 1
                if ((byteLength and 0x80) != 0) {
                    byteLength = ((byteLength and 0x7F) shl 8) or u8(b, offset)
                    offset += 1
                }
                b.decodeToString(offset, offset + byteLength)
            } else {
                var length = u16(b, offset)
                offset += 2
                if ((length and 0x8000) != 0) {
                    length = ((length and 0x7FFF) shl 16) or u16(b, offset)
                    offset += 2
                }
                require(length >= 0 && offset + 2L * length <= b.size)
                CharArray(length) { u16(b, offset + 2 * it).toChar() }.concatToString()
            }
        }
    }

    private fun readElement(
        b: ByteArray,
        chunkStart: Int,
        strings: List<String>,
    ): Pair<String?, Map<String, Any?>> {
        val nameIndex = u32(b, chunkStart + 20)
        val attributeStart = u16(b, chunkStart + 24)
        val attributeSize = u16(b, chunkStart + 26)
        val attributeCount = u16(b, chunkStart + 28)
        require(attributeSize >= MIN_ATTRIBUTE_SIZE)

        val first = chunkStart + ELEMENT_BODY_OFFSET + attributeStart
        val attributes = HashMap<String, Any?>()
        for (index in 0 until attributeCount) {
            val attribute = first + index * attributeSize
            val key = strings.getOrNull(u32(b, attribute + 4)) ?: continue
            attributes[key] = attributeValue(b, attribute, strings)
        }
        return strings.getOrNull(nameIndex) to attributes
    }

    private fun attributeValue(
        b: ByteArray,
        attribute: Int,
        strings: List<String>,
    ): Any? {
        val raw = u32(b, attribute + 8)
        val dataType = u8(b, attribute + 15)
        val data = u32(b, attribute + 16)
        return when (dataType) {
            TYPE_STRING -> strings.getOrNull(data)
            TYPE_INT_DEC, TYPE_INT_HEX -> data.toLong() and 0xFFFFFFFFL
            else -> if (raw != NO_ENTRY) strings.getOrNull(raw) else null
        }
    }

    private fun u8(
        b: ByteArray,
        offset: Int,
    ): Int = b[offset].toInt() and 0xFF

    private fun u16(
        b: ByteArray,
        offset: Int,
    ): Int = u8(b, offset) or (u8(b, offset + 1) shl 8)

    private fun u32(
        b: ByteArray,
        offset: Int,
    ): Int = u16(b, offset) or (u16(b, offset + 2) shl 16)
}
