package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.use

object FileDigest {
    fun sha256Hex(
        path: String,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): String {
        val hashing = HashingSink.sha256(blackholeSink())
        fileSystem.source(path.toPath()).buffer().use { it.readAll(hashing) }
        return hashing.hash.hex()
    }
}
