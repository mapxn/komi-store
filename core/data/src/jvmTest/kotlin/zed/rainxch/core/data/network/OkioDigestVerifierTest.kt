package zed.rainxch.core.data.network

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkioDigestVerifierTest {
    private val fileSystem = FileSystem.SYSTEM
    private val verifier = OkioDigestVerifier(fileSystem)
    private lateinit var dir: Path

    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-verify-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun fileWithAbc(): String {
        val file = dir / "abc.txt"
        fileSystem.write(file) { writeUtf8("abc") }
        return file.toString()
    }

    @Test
    fun matchingDigestPasses() =
        runBlocking {
            assertNull(verifier.verify(fileWithAbc(), "sha256:$abcDigest"))
            assertNull(verifier.verify(fileWithAbc(), abcDigest.uppercase()))
        }

    @Test
    fun mismatchingDigestReportsBothValues() =
        runBlocking {
            val result = verifier.verify(fileWithAbc(), "sha256:" + "0".repeat(64))
            assertNotNull(result)
            assertTrue(result.contains(abcDigest))
        }

    @Test
    fun missingFileIsReported() =
        runBlocking {
            val result = verifier.verify((dir / "nope").toString(), abcDigest)
            assertNotNull(result)
            assertTrue(result.startsWith("File not found"))
        }
}
