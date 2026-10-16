package zed.rainxch.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFileNameTest {
    @Test
    fun keepsOnlyTheLastPathSegment() {
        assertEquals("evil.ipa", DownloadFileName.sanitize("../../evil.ipa", "https://x/y"))
        assertEquals("c.apk", DownloadFileName.sanitize("a\\b\\c.apk", "https://x/y"))
        assertEquals("a.ipa", DownloadFileName.sanitize("/abs/path/a.ipa", "https://x/y"))
    }

    @Test
    fun stripsControlCharactersAndTrims() {
        assertEquals("foobar.ipa", DownloadFileName.sanitize("foo\u0000bar.ipa", "https://x/y"))
        assertEquals("spaced name.ipa", DownloadFileName.sanitize("  spaced name.ipa ", "https://x/y"))
    }

    @Test
    fun keepsUnicodeNames() {
        assertEquals("应用 1.0.ipa", DownloadFileName.sanitize("应用 1.0.ipa", "https://x/y"))
    }

    @Test
    fun fallsBackToTheUrlWhenNoNameIsSuggested() {
        assertEquals("app.ipa", DownloadFileName.sanitize(null, "https://x/y/app.ipa?token=1#frag"))
        assertEquals("app.ipa", DownloadFileName.sanitize("  ", "https://x/y/app.ipa"))
    }

    @Test
    fun generatesANameWhenNothingUsableExists() {
        assertTrue(DownloadFileName.sanitize(null, "https://x/").startsWith("asset-"))
    }

    @Test
    fun rejectsDotNames() {
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize("..", "https://x/y") }
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize("a/..", "https://x/y") }
        assertFailsWith<IllegalArgumentException> { DownloadFileName.sanitize(".", "https://x/y") }
    }

    @Test
    fun downloadedFilePathUsesTheSanitizedName() {
        assertEquals("/docs/evil.ipa", downloadedFilePath("/docs", "../evil.ipa").toString())
        assertNull(downloadedFilePath("/docs", null))
        assertNull(downloadedFilePath("/docs", " "))
        assertNull(downloadedFilePath("/docs", ".."))
    }
}
