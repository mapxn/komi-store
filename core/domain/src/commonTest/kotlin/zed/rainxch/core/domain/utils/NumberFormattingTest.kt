package zed.rainxch.core.domain.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormattingTest {
    @Test
    fun formatsFixedDecimals() {
        assertEquals("0.00", 0.0.formatFixed(2))
        assertEquals("1.0", 1.0.formatFixed(1))
        assertEquals("1.5", (1536.0 / 1024.0).formatFixed(1))
        assertEquals("123.46", 123.456.formatFixed(2))
    }

    @Test
    fun roundsHalfUp() {
        assertEquals("13", 12.5.formatFixed(0))
        assertEquals("2.3", 2.25.formatFixed(1))
        assertEquals("1000.0", 999.95.formatFixed(1))
    }

    @Test
    fun handlesNegativeValues() {
        assertEquals("-1.5", (-1.5).formatFixed(1))
        assertEquals("0.00", (-0.001).formatFixed(2))
    }

    @Test
    fun padsIntegersWithZeros() {
        assertEquals("05", 5.padStartZero(2))
        assertEquals("12", 12.padStartZero(2))
        assertEquals("007", 7.padStartZero(3))
    }
}
