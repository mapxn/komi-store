package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FileDigestTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-digest-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    @Test
    fun hashesFileContent() {
        val file = dir / "abc.txt"
        fileSystem.write(file) { writeUtf8("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileDigest.sha256Hex(file.toString(), fileSystem),
        )
    }

    @Test
    fun hashesEmptyFile() {
        val file = dir / "empty.bin"
        fileSystem.write(file) { }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FileDigest.sha256Hex(file.toString(), fileSystem),
        )
    }
}
