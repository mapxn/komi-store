package zed.rainxch.core.data.services

import okio.FileSystem
import okio.Path
import zed.rainxch.core.domain.model.installation.InstallSource
import zed.rainxch.core.domain.model.installation.InstalledApp
import zed.rainxch.core.domain.model.system.Platform
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultDownloadedFileStoreTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-store-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun locations() =
        object : FileLocationsProvider {
            override fun appDownloadsDir() = dir.toString()

            override fun userDownloadsDir() = dir.toString()

            override fun setExecutableIfNeeded(path: String) = Unit

            override fun getCacheSizeBytes() = 0L

            override fun clearCacheFiles() = true
        }

    private fun app(assetName: String?) =
        InstalledApp(
            packageName = "a.b.c",
            repoId = 1L,
            repoName = "repo",
            repoOwner = "owner",
            repoOwnerAvatarUrl = "",
            repoDescription = null,
            primaryLanguage = null,
            repoUrl = "",
            installedVersion = "1.0.0",
            installedAssetName = assetName,
            installedAssetUrl = null,
            latestVersion = null,
            latestAssetName = null,
            latestAssetUrl = null,
            latestAssetSize = null,
            appName = "App",
            installSource = InstallSource.THIS_APP,
            installedAt = 0L,
            lastCheckedAt = 0L,
            lastUpdatedAt = 0L,
            isUpdateAvailable = false,
            signingFingerprint = null,
            systemArchitecture = "AARCH64",
            fileExtension = "ipa",
        )

    private fun store(platform: Platform) = DefaultDownloadedFileStore(locations(), platform, fileSystem)

    @Test
    fun platformsThatDoNotTrackFilesAlwaysReportPresent() {
        assertTrue(store(Platform.ANDROID).isFilePresent(app("missing.apk")))
        assertTrue(store(Platform.MACOS).isFilePresent(app(null)))
    }

    @Test
    fun iosReportsPresentOnlyWhenTheFileExists() {
        fileSystem.write(dir / "owner_repo_app.ipa") { writeUtf8("x") }
        assertTrue(store(Platform.IOS).isFilePresent(app("App.ipa")))
        assertFalse(store(Platform.IOS).isFilePresent(app("Other.ipa")))
    }

    @Test
    fun iosTreatsAMissingAssetNameAsDeleted() {
        assertFalse(store(Platform.IOS).isFilePresent(app(null)))
        assertFalse(store(Platform.IOS).isFilePresent(app("  ")))
    }

    @Test
    fun iosNeverLooksOutsideTheDownloadsDirectory() {
        val outside = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-outside-${Random.nextLong().toString(16)}.ipa"
        fileSystem.write(outside) { writeUtf8("x") }
        try {
            assertFalse(store(Platform.IOS).isFilePresent(app("../${outside.name}")))
        } finally {
            fileSystem.delete(outside)
        }
    }

    @Test
    fun iosDetectsAFileDeletedAfterwards() {
        val file = dir / "owner_repo_app.ipa"
        fileSystem.write(file) { writeUtf8("x") }
        val store = store(Platform.IOS)
        assertTrue(store.isFilePresent(app("App.ipa")))
        fileSystem.delete(file)
        assertFalse(store.isFilePresent(app("App.ipa")))
    }
}
