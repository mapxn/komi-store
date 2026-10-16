package zed.rainxch.core.domain.utils

import java.security.MessageDigest
import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
