package zed.rainxch.core.data.packages

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
internal fun fixtureBytes(base64: String): ByteArray = Base64.decode(base64)

internal class TempDir {
    private val fileSystem = FileSystem.SYSTEM
    val path: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-pkg-${Random.nextLong().toString(16)}"

    init {
        fileSystem.createDirectories(path)
    }

    fun write(
        name: String,
        bytes: ByteArray,
    ): String {
        val file = path / name
        fileSystem.write(file) { write(bytes) }
        return file.toString()
    }

    fun zipEntry(
        zipFile: String,
        entryPath: String,
    ): ByteArray = fileSystem.openZip(zipFile.toPath()).read(entryPath.toPath()) { readByteArray() }

    fun delete() {
        fileSystem.deleteRecursively(path)
    }
}
