package zed.rainxch.core.domain.utils

expect fun sha256(input: ByteArray): ByteArray

expect fun secureRandomBytes(size: Int): ByteArray

fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
