package zed.rainxch.core.domain.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual fun sha256(input: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(
                if (input.isEmpty()) null else inputPinned.addressOf(0),
                input.size.convert(),
                digestPinned.addressOf(0).reinterpret(),
            )
        }
    }
    return digest
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed with status $status" }
    }
    return bytes
}
