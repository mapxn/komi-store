package zed.rainxch.core.data.utils

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import zed.rainxch.core.domain.system.LocalFileSystem

class OkioLocalFileSystem(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : LocalFileSystem {
    override fun exists(path: String): Boolean = fileSystem.exists(path.toPath())

    override fun size(path: String): Long =
        fileSystem
            .metadataOrNull(path.toPath())
            ?.takeIf { it.isRegularFile }
            ?.size
            ?: -1L

    override fun delete(path: String): Boolean {
        val target = path.toPath()
        if (!fileSystem.exists(target)) return false
        return try {
            fileSystem.delete(target)
            true
        } catch (_: IOException) {
            false
        }
    }
}
