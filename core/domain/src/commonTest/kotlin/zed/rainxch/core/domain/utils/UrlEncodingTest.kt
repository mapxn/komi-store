package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlEncodingTest {
    @Test
    fun keepsUnreservedCharacters() {
        assertEquals("ABCD-1234", encodeUrlComponent("ABCD-1234"))
        assertEquals("a_b.c~d", encodeUrlComponent("a_b.c~d"))
    }

    @Test
    fun encodesReservedCharacters() {
        assertEquals("a%20b", encodeUrlComponent("a b"))
        assertEquals("a%26b%3Dc", encodeUrlComponent("a&b=c"))
    }

    @Test
    fun encodesNonAsciiAsUtf8() {
        assertEquals("%C3%A9", encodeUrlComponent("é"))
        assertEquals("%E4%B8%AD", encodeUrlComponent("中"))
    }
}
