package zed.rainxch.core.data.packages

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PropertyListParserTest {
    private val temp = TempDir()

    @AfterTest
    fun tearDown() {
        temp.delete()
    }

    private fun plistBytes(base64Ipa: String): ByteArray {
        val ipa = temp.write("fixture.ipa", fixtureBytes(base64Ipa))
        return temp.zipEntry(ipa, "/Payload/Fixture.app/Info.plist")
    }

    private val expected =
        mapOf(
            "CFBundleIdentifier" to "zed.rainxch.fixture.ios",
            "CFBundleShortVersionString" to "2.3.4",
            "CFBundleVersion" to "17",
            "CFBundleDisplayName" to "Fixture iOS",
            "CFBundleName" to "FixtureName",
            "MinimumOSVersion" to "15.0",
        )

    @Test
    fun parsesXmlPlistTopLevelOnly() {
        val result = PropertyListParser.parseTopLevelStrings(plistBytes(PackageFixtures.IPA_XML_BASE64))
        assertNotNull(result)
        assertEquals(expected, result.filterKeys { it in expected })
    }

    @Test
    fun parsesBinaryPlistTopLevelOnly() {
        val result = PropertyListParser.parseTopLevelStrings(plistBytes(PackageFixtures.IPA_BINARY_BASE64))
        assertNotNull(result)
        assertEquals(expected, result.filterKeys { it in expected })
    }

    @Test
    fun decodesXmlEntitiesIntegersAndEmptyStrings() {
        val xml =
            """
            <plist version="1.0"><dict>
              <key>A</key><string>a &amp; b &lt;c&gt; &quot;d&quot;</string>
              <key>B</key><integer>9</integer>
              <key>C</key><string/>
              <key>D</key><true/>
              <key>E</key><array><string>ignored</string></array>
              <key>F</key><string>after</string>
            </dict></plist>
            """.trimIndent()
        val result = PropertyListParser.parseTopLevelStrings(xml.encodeToByteArray())
        assertEquals(mapOf("A" to "a & b <c> \"d\"", "B" to "9", "C" to "", "F" to "after"), result)
    }

    @Test
    fun rejectsDataThatIsNotAPropertyList() {
        assertNull(PropertyListParser.parseTopLevelStrings(ByteArray(0)))
        assertNull(PropertyListParser.parseTopLevelStrings("hello".encodeToByteArray()))
        assertNull(PropertyListParser.parseTopLevelStrings("bplist00garbage".encodeToByteArray()))
        assertNull(PropertyListParser.parseTopLevelStrings(ByteArray(300) { (it * 13).toByte() }))
    }

    @Test
    fun truncatedAndCorruptedBinaryPlistNeverThrows() {
        val bytes = plistBytes(PackageFixtures.IPA_BINARY_BASE64)
        for (length in 0..bytes.size) {
            PropertyListParser.parseTopLevelStrings(bytes.copyOf(length))
        }
        for (index in bytes.indices) {
            val copy = bytes.copyOf()
            copy[index] = (copy[index].toInt() xor 0xFF).toByte()
            PropertyListParser.parseTopLevelStrings(copy)
        }
    }
}
