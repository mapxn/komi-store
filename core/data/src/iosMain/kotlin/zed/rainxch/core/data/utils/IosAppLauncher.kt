package zed.rainxch.core.data.utils

import okio.FileSystem
import zed.rainxch.core.data.download.downloadedInstalledFilePath
import zed.rainxch.core.data.services.FileLocationsProvider
import zed.rainxch.core.domain.helpers.AppLauncher
import zed.rainxch.core.domain.helpers.ShareManager
import zed.rainxch.core.domain.logging.KomiStoreLogger
import zed.rainxch.core.domain.model.installation.InstalledApp

class IosAppLauncher(
    private val files: FileLocationsProvider,
    private val shareManager: ShareManager,
    private val logger: KomiStoreLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : AppLauncher {
    override suspend fun launchApp(installedApp: InstalledApp): Result<Unit> {
        val path = downloadedInstalledFilePath(files.userDownloadsDir(), installedApp)
        if (path == null || !fileSystem.exists(path)) {
            return Result.failure(IllegalStateException("Downloaded file is missing for ${installedApp.appName}"))
        }
        return if (shareManager.shareExistingFile(path.toString())) {
            Result.success(Unit)
        } else {
            logger.warn("Could not present the share sheet for ${installedApp.appName}")
            Result.failure(IllegalStateException("Could not share ${installedApp.appName}"))
        }
    }

    override suspend fun canLaunchApp(installedApp: InstalledApp): Boolean {
        val path = downloadedInstalledFilePath(files.userDownloadsDir(), installedApp) ?: return false
        return fileSystem.exists(path)
    }
}
