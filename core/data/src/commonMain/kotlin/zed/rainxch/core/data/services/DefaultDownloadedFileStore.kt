package zed.rainxch.core.data.services

import okio.FileSystem
import zed.rainxch.core.data.download.downloadedInstalledFilePath
import zed.rainxch.core.domain.model.installation.InstalledApp
import zed.rainxch.core.domain.model.system.Platform
import zed.rainxch.core.domain.system.DownloadedFileStore

class DefaultDownloadedFileStore(
    private val files: FileLocationsProvider,
    private val platform: Platform,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DownloadedFileStore {
    override fun isFilePresent(app: InstalledApp): Boolean {
        if (!platform.tracksDownloadedFiles) return true
        val path = downloadedInstalledFilePath(files.userDownloadsDir(), app) ?: return false
        return fileSystem.exists(path)
    }
}
