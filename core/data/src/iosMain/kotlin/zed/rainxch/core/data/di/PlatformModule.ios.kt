package zed.rainxch.core.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.CoroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module
import zed.rainxch.core.data.local.data_store.createAnnouncementsDataStore
import zed.rainxch.core.data.local.data_store.createDataStore
import zed.rainxch.core.data.local.db.AppDatabase
import zed.rainxch.core.data.local.db.initDatabase
import zed.rainxch.core.data.network.OkioDigestVerifier
import zed.rainxch.core.data.services.FileLocationsProvider
import zed.rainxch.core.data.services.IosAggressiveOemDetector
import zed.rainxch.core.data.services.IosApkInspector
import zed.rainxch.core.data.services.IosDownloadProgressNotifier
import zed.rainxch.core.data.services.IosDownloader
import zed.rainxch.core.data.services.IosFileLocationsProvider
import zed.rainxch.core.data.services.IosInstaller
import zed.rainxch.core.data.services.IosInstallerInfoExtractor
import zed.rainxch.core.data.services.IosInstallerStatusProvider
import zed.rainxch.core.data.services.IosLocalizationManager
import zed.rainxch.core.data.services.IosPackageMonitor
import zed.rainxch.core.data.services.IosPendingInstallNotifier
import zed.rainxch.core.data.services.IosUpdateScheduleManager
import zed.rainxch.core.data.services.LocalizationManager
import zed.rainxch.core.data.services.external.IosExternalAppScanner
import zed.rainxch.core.data.utils.IosAppLauncher
import zed.rainxch.core.data.utils.IosBrowserHelper
import zed.rainxch.core.data.utils.IosClipboardHelper
import zed.rainxch.core.data.utils.IosShareManager
import zed.rainxch.core.domain.helpers.AppLauncher
import zed.rainxch.core.domain.helpers.BrowserHelper
import zed.rainxch.core.domain.helpers.ClipboardHelper
import zed.rainxch.core.domain.helpers.ShareManager
import zed.rainxch.core.domain.network.DigestVerifier
import zed.rainxch.core.domain.network.Downloader
import zed.rainxch.core.domain.system.AggressiveOemDetector
import zed.rainxch.core.domain.system.ApkInspector
import zed.rainxch.core.domain.system.DownloadProgressNotifier
import zed.rainxch.core.domain.system.ExternalAppScanner
import zed.rainxch.core.domain.system.Installer
import zed.rainxch.core.domain.system.InstallerStatusProvider
import zed.rainxch.core.domain.system.PackageMonitor
import zed.rainxch.core.domain.system.PendingInstallNotifier
import zed.rainxch.core.domain.system.UpdateScheduleManager

actual val corePlatformModule =
    module {
        single<Downloader> {
            IosDownloader(
                files = get(),
                tokenStore = get(),
            )
        }

        single<Installer> {
            IosInstaller(
                installerInfoExtractor = IosInstallerInfoExtractor(),
                files = get(),
                installedAppDao = get(),
                scope = get<CoroutineScope>(),
                logger = get(),
            )
        }

        single<FileLocationsProvider> {
            IosFileLocationsProvider()
        }

        single<AggressiveOemDetector> {
            IosAggressiveOemDetector()
        }

        single<PackageMonitor> {
            IosPackageMonitor()
        }

        single<ApkInspector> {
            IosApkInspector()
        }

        single<ExternalAppScanner> {
            IosExternalAppScanner()
        }

        single<LocalizationManager> {
            IosLocalizationManager()
        }

        single<AppDatabase> {
            initDatabase()
        }

        single<DataStore<Preferences>> {
            createDataStore()
        }

        single<DataStore<Preferences>>(qualifier = named("announcements")) {
            createAnnouncementsDataStore()
        }

        single<KSafe>(qualifier = named("tokens")) {
            KSafe(fileName = "ghs_tokens")
        }

        single<KSafe>(qualifier = named("prefs")) {
            KSafe(fileName = "ghs_prefs")
        }

        single<KSafe>(qualifier = named("announcements_cache")) {
            KSafe(fileName = "ghs_announcements")
        }

        single<BrowserHelper> {
            IosBrowserHelper()
        }

        single<DigestVerifier> {
            OkioDigestVerifier()
        }

        single<ClipboardHelper> {
            IosClipboardHelper()
        }

        single<ShareManager> {
            IosShareManager()
        }

        single<AppLauncher> {
            IosAppLauncher(
                files = get(),
                shareManager = get(),
                logger = get(),
            )
        }

        single<InstallerStatusProvider> {
            IosInstallerStatusProvider()
        }

        single<UpdateScheduleManager> {
            IosUpdateScheduleManager()
        }

        single<PendingInstallNotifier> {
            IosPendingInstallNotifier()
        }

        single<DownloadProgressNotifier> {
            IosDownloadProgressNotifier()
        }
    }
