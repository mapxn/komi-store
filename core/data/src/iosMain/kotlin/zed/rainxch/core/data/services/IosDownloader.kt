package zed.rainxch.core.data.services

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import zed.rainxch.core.data.data_source.TokenStore
import zed.rainxch.core.data.download.DownloadFileName
import zed.rainxch.core.data.download.OrphanPolicy
import zed.rainxch.core.data.download.OrphanVerdict
import zed.rainxch.core.data.download.PartialNaming
import zed.rainxch.core.data.download.StreamingFileDownloader
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.data.network.createPlatformHttpClient
import zed.rainxch.core.domain.model.installation.DownloadProgress
import zed.rainxch.core.domain.model.settings.ProxyScope
import zed.rainxch.core.domain.network.AssetIdentity
import zed.rainxch.core.domain.network.Downloader
import kotlin.coroutines.cancellation.CancellationException

class IosDownloader(
    private val files: FileLocationsProvider,
    private val tokenStore: TokenStore,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : Downloader {
    private val streaming =
        StreamingFileDownloader(
            clientFactory = {
                createPlatformHttpClient(ProxyManager.currentConfig(ProxyScope.DOWNLOAD)).config {
                    followRedirects = false
                    install(HttpTimeout) {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }
            },
            tokenProvider = { githubToken() },
            fileSystem = fileSystem,
        )

    override fun download(
        url: String,
        suggestedFileName: String?,
        bypassMirror: Boolean,
        identity: AssetIdentity?,
    ): Flow<DownloadProgress> =
        streaming
            .download(url, files.userDownloadsDir(), suggestedFileName)
            .flowOn(Dispatchers.IO)

    override suspend fun saveToFile(
        url: String,
        suggestedFileName: String?,
    ): String =
        withContext(Dispatchers.IO) {
            val name = DownloadFileName.sanitize(suggestedFileName, url)
            streaming.download(url, files.userDownloadsDir(), name).collect { }
            (files.userDownloadsDir().toPath() / name).toString()
        }

    override suspend fun getDownloadedFilePath(fileName: String): String? =
        withContext(Dispatchers.IO) {
            val path = downloadedFilePath(files.userDownloadsDir(), fileName) ?: return@withContext null
            val size = fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size ?: 0L
            if (size > 0) path.toString() else null
        }

    override suspend fun cancelDownload(fileName: String): Boolean = streaming.cancel(fileName)

    override suspend fun discardPartial(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            streaming.cancel(fileName)
            val dir = files.userDownloadsDir().toPath()
            val assetName = DownloadFileName.sanitize(fileName, "")
            var removed = false
            for (name in listOf(PartialNaming.part(assetName), PartialNaming.meta(assetName))) {
                val path = dir / name
                if (fileSystem.exists(path)) {
                    fileSystem.delete(path, mustExist = false)
                    removed = true
                }
            }
            removed
        }

    override suspend fun reclaimOrphanedPartials(claimedNames: Set<String>): Int =
        withContext(Dispatchers.IO) {
            val dir = files.userDownloadsDir().toPath()
            if (!fileSystem.exists(dir)) return@withContext 0

            val names = fileSystem.list(dir).map { it.name }
            val assetNames =
                names
                    .mapNotNull { PartialNaming.assetNameOfPart(it) ?: PartialNaming.assetNameOfMeta(it) }
                    .toSet()

            var removed = 0
            for (assetName in assetNames) {
                if (assetName in claimedNames) continue
                val part = dir / PartialNaming.part(assetName)
                val meta = dir / PartialNaming.meta(assetName)
                val verdict =
                    OrphanPolicy.classify(
                        partExists = fileSystem.exists(part),
                        metaExists = fileSystem.exists(meta),
                        isClaimed = false,
                    )
                removed += applyVerdict(verdict, dir, assetName)
                names
                    .filter { it != part.name && PartialNaming.assetNameOfPart(it) == assetName }
                    .forEach { legacyName ->
                        fileSystem.delete(dir / legacyName, mustExist = false)
                        removed++
                    }
            }
            removed
        }

    private fun applyVerdict(
        verdict: OrphanVerdict,
        directory: Path,
        assetName: String,
    ): Int {
        val target =
            when (verdict) {
                OrphanVerdict.KEEP -> return 0
                OrphanVerdict.DROP_PART_ONLY -> directory / PartialNaming.part(assetName)
                OrphanVerdict.DROP_META_ONLY -> directory / PartialNaming.meta(assetName)
            }
        fileSystem.delete(target, mustExist = false)
        return 1
    }

    private suspend fun githubToken(): String? =
        try {
            tokenStore
                .currentToken()
                ?.accessToken
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
