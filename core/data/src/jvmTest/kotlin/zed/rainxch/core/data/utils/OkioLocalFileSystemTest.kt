package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OkioLocalFileSystemTest {
    private val fileSystem = FileSystem.SYSTEM
    private val local = OkioLocalFileSystem(fileSystem)
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-local-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    @Test
    fun reportsExistenceAndSize() {
        val file = dir / "a.bin"
        fileSystem.write(file) { writeUtf8("12345") }
        assertTrue(local.exists(file.toString()))
        assertEquals(5L, local.size(file.toString()))
    }

    @Test
    fun missingPathHasNegativeSize() {
        val missing = (dir / "missing").toString()
        assertFalse(local.exists(missing))
        assertEquals(-1L, local.size(missing))
    }

    @Test
    fun directoryIsNotARegularFile() {
        assertEquals(-1L, local.size(dir.toString()))
    }

    @Test
    fun deleteRemovesFileAndReportsMissing() {
        val file = dir / "gone.bin"
        fileSystem.write(file) { writeUtf8("x") }
        assertTrue(local.delete(file.toString()))
        assertFalse(local.exists(file.toString()))
        assertFalse(local.delete(file.toString()))
    }
}
