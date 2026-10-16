package zed.rainxch.core.data.packages

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidManifestParserTest {
    private val temp = TempDir()

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun manifestBytes(): ByteArray {
        val apk = temp.write("fixture.apk", fixtureBytes(PackageFixtures.APK_BASE64))
        return temp.zipEntry(apk, "/AndroidManifest.xml")
    }

    @Test
    fun parsesPackageVersionAndLabel() {
        assertEquals(
            ManifestInfo(
                packageName = "zed.rainxch.fixture",
                versionName = "1.2.3",
                versionCode = 42L,
                label = "Fixture App",
            ),
            AndroidManifestParser.parse(manifestBytes()),
        )
    }

    @Test
    fun rejectsBytesThatAreNotABinaryManifest() {
        assertNull(AndroidManifestParser.parse(ByteArray(0)))
        assertNull(AndroidManifestParser.parse(byteArrayOf(1, 2, 3)))
        assertNull(AndroidManifestParser.parse(ByteArray(200) { (it * 7).toByte() }))
        assertNull(AndroidManifestParser.parse("<manifest package=\"a.b\"/>".encodeToByteArray()))
    }

    @Test
    fun truncatedInputNeverThrows() {
        val bytes = manifestBytes()
        for (length in 0..bytes.size) {
            AndroidManifestParser.parse(bytes.copyOf(length))
        }
    }

    @Test
    fun corruptedInputNeverThrows() {
        val bytes = manifestBytes()
        for (index in bytes.indices) {
            val copy = bytes.copyOf()
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            AndroidManifestParser.parse(copy)
        }
    }
}
