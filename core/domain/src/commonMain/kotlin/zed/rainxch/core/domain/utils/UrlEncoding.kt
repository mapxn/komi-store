package zed.rainxch.core.domain.utils

private const val HEX_DIGITS = "0123456789ABCDEF"

fun encodeUrlComponent(value: String): String =
    buildString {
        for (byte in value.encodeToByteArray()) {
            val unsigned = byte.toInt() and 0xFF
            val char = unsigned.toChar()
            val unreserved =
                char in 'A'..'Z' ||
                    char in 'a'..'z' ||
                    char in '0'..'9' ||
                    char == '-' ||
                    char == '_' ||
                    char == '.' ||
                    char == '~'
            if (unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[unsigned shr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }
