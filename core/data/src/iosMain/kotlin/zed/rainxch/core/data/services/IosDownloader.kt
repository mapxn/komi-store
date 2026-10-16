package zed.rainxch.core.data.services

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import zed.rainxch.core.data.data_source.TokenStore
import zed.rainxch.core.data.download.DownloadFileName
import zed.rainxch.core.data.download.StreamingFileDownloader
import zed.rainxch.core.data.download.downloadedFilePath
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.data.network.createPlatformHttpClient
import zed.rainxch.core.domain.model.installation.DownloadProgress
import zed.rainxch.core.domain.model.settings.ProxyScope
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

    private suspend fun githubToken(): String? =
        try {
            tokenStore.currentToken()?.accessToken?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
