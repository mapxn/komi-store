package zed.rainxch.core.data.services

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import zed.rainxch.core.data.local.IosAppDataPaths

class IosFileLocationsProvider(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileLocationsProvider {
    override fun appDownloadsDir(): String = ensure("${IosAppDataPaths.cachesDir()}/KomiStore/Downloads")

    override fun userDownloadsDir(): String = ensure("${IosAppDataPaths.documentsDir()}/Komi Store Downloads")

    override fun setExecutableIfNeeded(path: String) = Unit

    override fun getCacheSizeBytes(): Long = sizeOf(appDownloadsDir().toPath()) + sizeOf(userDownloadsDir().toPath())

    override fun clearCacheFiles(): Boolean = clear(appDownloadsDir().toPath()) && clear(userDownloadsDir().toPath())

    private fun ensure(path: String): String {
        fileSystem.createDirectories(path.toPath())
        return path
    }

    private fun sizeOf(directory: Path): Long =
        fileSystem
            .listRecursively(directory)
            .sumOf { fileSystem.metadataOrNull(it)?.takeIf { metadata -> metadata.isRegularFile }?.size ?: 0L }

    private fun clear(directory: Path): Boolean {
        var allDeleted = true
        for (child in fileSystem.list(directory)) {
            try {
                fileSystem.deleteRecursively(child)
            } catch (_: IOException) {
                allDeleted = false
            }
        }
        return allDeleted
    }
}
