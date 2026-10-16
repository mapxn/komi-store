package zed.rainxch.core.data.download

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import zed.rainxch.core.data.network.GithubAssetAuth
import zed.rainxch.core.domain.model.installation.DownloadProgress
import kotlin.random.Random

class StreamingFileDownloader(
    private val clientFactory: () -> HttpClient,
    private val tokenProvider: suspend () -> String?,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val registryLock = Mutex()
    private val active = HashMap<String, MutableSet<Job>>()

    fun download(
        url: String,
        directory: String,
        fileName: String?,
    ): Flow<DownloadProgress> =
        channelFlow {
            val safeName = DownloadFileName.sanitize(fileName, url)
            val directoryPath = directory.toPath()
            fileSystem.createDirectories(directoryPath)
            val destination = directoryPath / safeName
            val partFile = directoryPath / "$safeName.part-${Random.nextLong().toString(16)}"

            val worker =
                launch {
                    try {
                        val (downloaded, total) =
                            transfer(url, partFile) { bytes, totalBytes ->
                                send(DownloadProgress(bytes, totalBytes, percentOf(bytes, totalBytes)))
                            }
                        fileSystem.atomicMove(partFile, destination)
                        send(DownloadProgress(downloaded, total, percentOf(downloaded, total) ?: 100))
                    } catch (e: Throwable) {
                        fileSystem.delete(partFile, mustExist = false)
                        throw e
                    }
                }

            registryLock.withLock { active.getOrPut(safeName) { mutableSetOf() }.add(worker) }
            try {
                worker.join()
            } finally {
                withContext(NonCancellable) {
                    registryLock.withLock { active[safeName]?.remove(worker) }
                }
            }
            if (worker.isCancelled) throw IOException("Download cancelled")
        }

    suspend fun cancel(fileName: String): Boolean {
        val name =
            try {
                DownloadFileName.sanitize(fileName, "")
            } catch (_: IllegalArgumentException) {
                return false
            }
        val jobs = registryLock.withLock { active.remove(name)?.toList().orEmpty() }
        jobs.forEach { it.cancel() }
        return jobs.isNotEmpty()
    }

    private suspend fun transfer(
        url: String,
        partFile: Path,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Pair<Long, Long?> {
        val http = clientFactory()
        try {
            val token = tokenProvider()
            var currentUrl = url
            var hop = 0
            while (true) {
                val requestUrl = currentUrl
                val outcome =
                    http
                        .prepareGet(requestUrl) {
                            if (hop == 0 && token != null && GithubAssetAuth.isGithubHost(requestUrl)) {
                                header(HttpHeaders.Authorization, "Bearer $token")
                                if (GithubAssetAuth.isGithubApiHost(requestUrl)) {
                                    header(HttpHeaders.Accept, "application/octet-stream")
                                }
                            }
                        }.execute { response -> handle(response, partFile, onProgress) }

                when (outcome) {
                    is Outcome.Done -> return outcome.downloaded to outcome.total
                    is Outcome.Redirect -> {
                        hop += 1
                        if (hop > MAX_REDIRECTS) throw IOException("Too many redirects")
                        currentUrl = URLBuilder(requestUrl).takeFrom(outcome.location).buildString()
                    }
                }
            }
        } finally {
            http.close()
        }
    }

    private suspend fun handle(
        response: HttpResponse,
        partFile: Path,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Outcome {
        val status = response.status.value
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location] ?: throw IOException("Redirect $status without Location")
            return Outcome.Redirect(location)
        }
        if (!response.status.isSuccess()) throw IOException("Unexpected code $status")

        val total = response.contentLength()?.takeIf { it > 0 }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = 0L
        fileSystem.sink(partFile).buffer().use { sink ->
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                downloaded += read
                onProgress(downloaded, total)
            }
        }
        if (downloaded == 0L) throw IOException("Download produced an empty file")
        return Outcome.Done(downloaded, total)
    }

    private fun percentOf(
        downloaded: Long,
        total: Long?,
    ): Int? = if (total != null && total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null

    private sealed interface Outcome {
        data class Redirect(
            val location: String,
        ) : Outcome

        data class Done(
            val downloaded: Long,
            val total: Long?,
        ) : Outcome
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_REDIRECTS = 10
    }
}
