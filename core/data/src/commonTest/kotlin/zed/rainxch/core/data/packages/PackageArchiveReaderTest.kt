package zed.rainxch.core.data.packages

import okio.FileSystem
import zed.rainxch.core.domain.model.apk.ApkPackageInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageArchiveReaderTest {
    private val temp = TempDir()
    private val reader = PackageArchiveReader(FileSystem.SYSTEM)

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun write(
        name: String,
        base64: String,
    ): String = temp.write(name, fixtureBytes(base64))

    @Test
    fun readsApk() {
        assertEquals(
            ApkPackageInfo(
                packageName = "zed.rainxch.fixture",
                versionName = "1.2.3",
                versionCode = 42L,
                appName = "Fixture App",
                signingFingerprint = null,
            ),
            reader.read(write("app.apk", PackageFixtures.APK_BASE64)),
        )
    }

    @Test
    fun readsIpaWithXmlAndBinaryPlist() {
        val expected =
            ApkPackageInfo(
                packageName = "zed.rainxch.fixture.ios",
                versionName = "2.3.4",
                versionCode = 17L,
                appName = "Fixture iOS",
                signingFingerprint = null,
            )
        assertEquals(expected, reader.read(write("xml.ipa", PackageFixtures.IPA_XML_BASE64)))
        assertEquals(expected, reader.read(write("bin.IPA", PackageFixtures.IPA_BINARY_BASE64)))
    }

    @Test
    fun nonIntegerBuildNumberBecomesVersionCodeZero() {
        val info = reader.read(write("odd.ipa", PackageFixtures.IPA_ODD_VERSION_BASE64))
        assertEquals(0L, info?.versionCode)
        assertEquals("1.2.3.4", info?.versionName)
    }

    @Test
    fun shortVersionIsPreferredForVersionName() {
        val info = reader.read(write("short.ipa", PackageFixtures.IPA_SHORT_VERSION_BASE64))
        assertEquals("5.0", info?.versionName)
        assertEquals(99L, info?.versionCode)
    }

    @Test
    fun unknownExtensionMissingFileAndEmptyFileReturnNull() {
        assertNull(reader.read(write("app.zip", PackageFixtures.APK_BASE64)))
        assertNull(reader.read(temp.path.toString() + "/missing.apk"))
        assertNull(reader.read(temp.write("empty.ipa", ByteArray(0))))
    }

    @Test
    fun brokenPackagesReturnNull() {
        val apk = fixtureBytes(PackageFixtures.APK_BASE64)
        assertNull(reader.read(temp.write("truncated.apk", apk.copyOf(100))))
        assertNull(reader.read(temp.write("notzip.apk", "not a zip file at all".encodeToByteArray())))
        assertNull(reader.read(write("nomanifest.apk", PackageFixtures.ZIP_NO_MANIFEST_BASE64)))
        assertNull(reader.read(write("nopayload.ipa", PackageFixtures.ZIP_NO_MANIFEST_BASE64)))
        assertNull(reader.read(write("noplist.ipa", PackageFixtures.ZIP_NO_PLIST_BASE64)))
        assertNull(reader.read(write("badplist.ipa", PackageFixtures.ZIP_BAD_PLIST_BASE64)))
    }
}
