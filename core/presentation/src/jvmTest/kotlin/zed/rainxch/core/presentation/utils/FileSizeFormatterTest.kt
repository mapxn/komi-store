package zed.rainxch.core.presentation.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSizeFormatterTest {
    @Test
    fun formatsBytesBelowOneKilobyte() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun formatsLargerUnits() {
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("1.0 MB", formatFileSize(1_048_576))
        assertEquals("5.0 GB", formatFileSize(5L * 1_073_741_824L))
    }

    @Test
    fun respectsDecimals() {
        assertEquals("1.50 KB", formatFileSize(1536, decimals = 2))
    }
}
