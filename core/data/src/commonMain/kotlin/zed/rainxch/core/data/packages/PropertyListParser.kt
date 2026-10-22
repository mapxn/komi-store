package zed.rainxch.core.data.packages

internal object PropertyListParser {
    private val BINARY_MAGIC = "bplist00".encodeToByteArray()
    private val XML_TOKEN = Regex("""<(/?)([A-Za-z]+)\s*(/?)>|([^<]+)""")

    fun parseTopLevelStrings(bytes: ByteArray): Map<String, String>? =
        try {
            if (isBinary(bytes)) {
                BinaryPlist(bytes).topLevelStrings()
            } else {
                parseXml(bytes.decodeToString())
            }
        } catch (_: Exception) {
            null
        }

    private fun isBinary(bytes: ByteArray): Boolean =
        bytes.size >= BINARY_MAGIC.size && BINARY_MAGIC.indices.all { bytes[it] == BINARY_MAGIC[it] }

    private fun parseXml(text: String): Map<String, String>? {
        val start = text.indexOf("<dict>")
        if (start < 0) return null

        var depth = 0
        var key: String? = null
        var pending: StringBuilder? = null
        val result = LinkedHashMap<String, String>()

        for (match in XML_TOKEN.findAll(text, start)) {
            val (closing, name, selfClosing, characters) = match.destructured
            if (name.isEmpty()) {
                pending = (pending ?: StringBuilder()).append(characters)
                continue
            }

            val isClose = closing.isNotEmpty()
            val isSelf = selfClosing.isNotEmpty()
            val isContainer = name == "dict" || name == "array"

            when {
                isContainer && !isClose && !isSelf -> {
                    depth++
                    pending = null
                }

                isContainer && isClose -> {
                    depth--
                    pending = null
                    key = null
                    if (depth == 0) break
                }

                depth != 1 -> {
                    pending = null
                }

                name == "key" && !isClose && !isSelf -> {
                    pending = null
                }

                name == "key" && isClose -> {
                    key = pending?.toString()?.trim()
                    pending = null
                }

                isClose || isSelf -> {
                    val currentKey = key
                    if (currentKey != null) {
                        when {
                            name == "string" && isSelf -> {
                                result[currentKey] = ""
                            }

                            name == "string" -> {
                                result[currentKey] = unescape(pending?.toString().orEmpty())
                            }

                            (name == "integer" || name == "real") && !isSelf -> {
                                result[currentKey] = pending?.toString().orEmpty().trim()
                            }
                        }
                    }
                    key = null
                    pending = null
                }

                else -> {
                    pending = null
                }
            }
        }
        return result
    }

    private fun unescape(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private class BinaryPlist(
        private val b: ByteArray,
    ) {
        private val offsetSize: Int
        private val refSize: Int
        private val objectCount: Int
        private val topObject: Int
        private val tableOffset: Int

        init {
            require(b.size >= MIN_SIZE)
            val trailer = b.size - TRAILER_SIZE
            offsetSize = u8(trailer + 6)
            refSize = u8(trailer + 7)
            require(offsetSize in 1..8 && refSize in 1..8)
            objectCount = checkedInt(readBigEndian(trailer + 8, 8))
            topObject = checkedInt(readBigEndian(trailer + 16, 8))
            tableOffset = checkedInt(readBigEndian(trailer + 24, 8))
            require(objectCount in 1..MAX_OBJECTS && topObject in 0 until objectCount)
            require(tableOffset >= BINARY_MAGIC.size && tableOffset.toLong() + objectCount.toLong() * offsetSize <= b.size)
        }

        fun topLevelStrings(): Map<String, String>? {
            val offset = objectOffset(topObject)
            if ((u8(offset) shr 4) != DICT_TYPE) return null

            val (count, start) = readLength(offset)
            require(start.toLong() + 2L * count * refSize <= b.size)
            val result = LinkedHashMap<String, String>()
            for (index in 0 until count) {
                val key = scalar(readRef(start + index * refSize)) as? String ?: continue
                when (val value = scalar(readRef(start + (count + index) * refSize))) {
                    is String -> result[key] = value
                    is Long -> result[key] = value.toString()
                }
            }
            return result
        }

        private fun scalar(index: Int): Any? {
            val offset = objectOffset(index)
            val marker = u8(offset)
            return when (marker shr 4) {
                0x0 -> {
                    when (marker) {
                        0x08 -> false
                        0x09 -> true
                        else -> null
                    }
                }

                0x1 -> {
                    val size = 1 shl (marker and 0x0F)
                    if (size > 8) null else readBigEndian(offset + 1, size)
                }

                0x5 -> {
                    val (length, start) = readLength(offset)
                    require(start.toLong() + length <= b.size)
                    b.decodeToString(start, start + length)
                }

                0x6 -> {
                    val (length, start) = readLength(offset)
                    require(start.toLong() + 2L * length <= b.size)
                    CharArray(length) { ((u8(start + 2 * it) shl 8) or u8(start + 2 * it + 1)).toChar() }
                        .concatToString()
                }

                else -> {
                    null
                }
            }
        }

        private fun readLength(offset: Int): Pair<Int, Int> {
            val low = u8(offset) and 0x0F
            if (low != 0x0F) return low to offset + 1
            val intMarker = u8(offset + 1)
            require((intMarker shr 4) == 0x1)
            val size = 1 shl (intMarker and 0x0F)
            require(size <= 4)
            return checkedInt(readBigEndian(offset + 2, size)) to offset + 2 + size
        }

        private fun objectOffset(index: Int): Int {
            val offset = checkedInt(readBigEndian(tableOffset + index * offsetSize, offsetSize))
            require(offset in 0 until b.size)
            return offset
        }

        private fun readRef(position: Int): Int {
            val ref = checkedInt(readBigEndian(position, refSize))
            require(ref in 0 until objectCount)
            return ref
        }

        private fun readBigEndian(
            offset: Int,
            size: Int,
        ): Long {
            var value = 0L
            for (index in 0 until size) {
                value = (value shl 8) or u8(offset + index).toLong()
            }
            return value
        }

        private fun checkedInt(value: Long): Int {
            require(value in 0..Int.MAX_VALUE.toLong())
            return value.toInt()
        }

        private fun u8(offset: Int): Int = b[offset].toInt() and 0xFF

        private companion object {
            const val TRAILER_SIZE = 32
            const val MIN_SIZE = 8 + TRAILER_SIZE
            const val MAX_OBJECTS = 1_000_000
            const val DICT_TYPE = 0xD
        }
    }
}
