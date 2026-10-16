package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CryptoTest {
    @Test
    fun sha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256OfEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun secureRandomBytesHasRequestedSizeAndVaries() {
        val first = secureRandomBytes(32)
        val second = secureRandomBytes(32)
        assertEquals(32, first.size)
        assertEquals(0, secureRandomBytes(0).size)
        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun toHexIsLowercaseAndZeroPadded() {
        assertEquals("000fff", byteArrayOf(0, 15, -1).toHex())
    }
}
