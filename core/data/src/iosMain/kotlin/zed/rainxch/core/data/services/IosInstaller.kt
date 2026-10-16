package zed.rainxch.core.data.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.FileSystem
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.local.db.dao.InstalledAppDao
import zed.rainxch.core.data.packages.IosAssetPolicy
import zed.rainxch.core.domain.logging.KomiStoreLogger
import zed.rainxch.core.domain.model.account.github.GithubAsset
import zed.rainxch.core.domain.model.system.SystemArchitecture
import zed.rainxch.core.domain.system.InstallOutcome
import zed.rainxch.core.domain.system.Installer
import zed.rainxch.core.domain.system.InstallerInfoExtractor
import kotlin.coroutines.cancellation.CancellationException

class IosInstaller(
    private val installerInfoExtractor: InstallerInfoExtractor,
    private val files: FileLocationsProvider,
    private val installedAppDao: InstalledAppDao,
    private val scope: CoroutineScope,
    private val logger: KomiStoreLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Installer {
    override suspend fun isSupported(extOrMime: String): Boolean = extOrMime.lowercase().removePrefix(".") in SUPPORTED_EXTENSIONS

    override suspend fun ensurePermissionsOrThrow(extOrMime: String) = Unit

    override suspend fun install(
        filePath: String,
        extOrMime: String,
    ): InstallOutcome = InstallOutcome.COMPLETED

    override fun uninstall(packageName: String) {
        scope.launch {
            try {
                val app = installedAppDao.getAppByPackage(packageName) ?: return@launch
                downloadedFilePath(files.userDownloadsDir(), app.installedAssetName)
                    ?.let { fileSystem.delete(it, mustExist = false) }
                installedAppDao.deleteByPackageName(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to delete download for $packageName: ${e.message}", e)
            }
        }
    }

    override fun isAssetInstallable(assetName: String): Boolean = IosAssetPolicy.isDownloadable(assetName)

    override fun choosePrimaryAsset(assets: List<GithubAsset>): GithubAsset? = IosAssetPolicy.choosePrimary(assets)

    override fun detectSystemArchitecture(): SystemArchitecture = SystemArchitecture.AARCH64

    override fun isObtainiumInstalled(): Boolean = false

    override fun openInObtainium(
        repoOwner: String,
        repoName: String,
        onOpenInstaller: () -> Unit,
    ) = Unit

    override fun isAppManagerInstalled(): Boolean = false

    override fun openInAppManager(
        filePath: String,
        onOpenInstaller: () -> Unit,
    ) = Unit

    override fun getApkInfoExtractor(): InstallerInfoExtractor = installerInfoExtractor

    override fun openApp(packageName: String): Boolean = false

    override fun openWithExternalInstaller(filePath: String) = Unit

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("ipa", "apk")
    }
}
