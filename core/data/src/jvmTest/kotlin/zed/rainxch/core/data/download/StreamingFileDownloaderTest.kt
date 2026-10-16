package zed.rainxch.core.data.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import zed.rainxch.core.domain.model.installation.DownloadProgress
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingFileDownloaderTest {
    private val fileSystem = FileSystem.SYSTEM
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "komi-dl-${Random.nextLong().toString(16)}"
        fileSystem.createDirectories(dir)
    }

    @AfterTest
    fun tearDown() {
        fileSystem.deleteRecursively(dir)
    }

    private fun downloader(
        token: String? = null,
        handler: MockRequestHandler,
    ) = StreamingFileDownloader(
        clientFactory = { HttpClient(MockEngine(handler)) { followRedirects = false } },
        tokenProvider = { token },
        fileSystem = fileSystem,
    )

    private suspend fun StreamingFileDownloader.collect(
        url: String,
        name: String?,
    ): List<DownloadProgress> = download(url, dir.toString(), name).toList()

    private fun filesInDir(): List<String> = fileSystem.list(dir).map { it.name }

    @Test
    fun savesTheFileAndReportsProgress() =
        runBlocking {
            val body = "hello".encodeToByteArray()
            val progress =
                downloader {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
                }.collect("https://example.com/a.ipa", "a.ipa")

            assertEquals("hello", fileSystem.read(dir / "a.ipa") { readUtf8() })
            assertEquals(5L, progress.last().bytesDownloaded)
            assertEquals(100, progress.last().percent)
            assertEquals(listOf("a.ipa"), filesInDir())
        }

    @Test
    fun progressPercentIsNullWhenLengthIsUnknown() =
        runBlocking {
            val progress =
                downloader { respond("abc".encodeToByteArray(), HttpStatusCode.OK) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            assertNull(progress.last().totalBytes)
            assertEquals(3L, progress.last().bytesDownloaded)
        }

    @Test
    fun httpErrorLeavesNoFile() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader { respond("nope", HttpStatusCode.NotFound) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun emptyBodyFailsAndLeavesNoFile() =
        runBlocking {
            assertFailsWith<Exception> {
                downloader { respond(ByteArray(0), HttpStatusCode.OK) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun existingFileIsReplaced() =
        runBlocking {
            fileSystem.write(dir / "a.ipa") { writeUtf8("old content that is longer") }
            downloader { respond("new!".encodeToByteArray(), HttpStatusCode.OK) }
                .collect("https://example.com/a.ipa", "a.ipa")
            assertEquals("new!", fileSystem.read(dir / "a.ipa") { readUtf8() })
            assertEquals(listOf("a.ipa"), filesInDir())
        }

    @Test
    fun pathTraversalNamesStayInsideTheDirectory() =
        runBlocking {
            downloader { respond("x".encodeToByteArray(), HttpStatusCode.OK) }
                .collect("https://example.com/a.ipa", "../../evil.ipa")
            assertEquals(listOf("evil.ipa"), filesInDir())
        }

    @Test
    fun authorizationIsOnlySentOnTheFirstHop() =
        runBlocking {
            val seen = mutableListOf<Triple<String, String?, String?>>()
            downloader(token = "secret") { request ->
                seen += Triple(
                    request.url.toString(),
                    request.headers[HttpHeaders.Authorization],
                    request.headers[HttpHeaders.Accept],
                )
                if (request.url.host == "api.github.com") {
                    respond(
                        ByteArray(0),
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://example.com/blob/a.ipa"),
                    )
                } else {
                    respond("data".encodeToByteArray(), HttpStatusCode.OK)
                }
            }.collect("https://api.github.com/repos/o/r/releases/assets/1", "a.ipa")

            assertEquals(2, seen.size)
            assertEquals("Bearer secret", seen[0].second)
            assertEquals("application/octet-stream", seen[0].third)
            assertEquals("https://example.com/blob/a.ipa", seen[1].first)
            assertNull(seen[1].second)
        }

    @Test
    fun tokenIsNotSentToNonGithubHosts() =
        runBlocking {
            var authorization: String? = "unset"
            downloader(token = "secret") { request ->
                authorization = request.headers[HttpHeaders.Authorization]
                respond("data".encodeToByteArray(), HttpStatusCode.OK)
            }.collect("https://example.com/a.ipa", "a.ipa")
            assertNull(authorization)
        }

    @Test
    fun relativeRedirectIsResolvedAgainstTheCurrentUrl() =
        runBlocking {
            val urls = mutableListOf<String>()
            downloader { request ->
                urls += request.url.toString()
                if (request.url.encodedPath == "/start") {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/final/a.ipa"))
                } else {
                    respond("data".encodeToByteArray(), HttpStatusCode.OK)
                }
            }.collect("https://example.com/start", "a.ipa")
            assertEquals(listOf("https://example.com/start", "https://example.com/final/a.ipa"), urls)
        }

    @Test
    fun redirectLoopFailsAndLeavesNoFile() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/again"))
                }.collect("https://example.com/a.ipa", "a.ipa")
            }
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun redirectWithoutLocationFails() =
        runBlocking {
            assertFailsWith<IOException> {
                downloader { respond(ByteArray(0), HttpStatusCode.Found) }
                    .collect("https://example.com/a.ipa", "a.ipa")
            }
            Unit
        }

    @Test
    fun cancelByNameFailsTheDownloadAndLeavesNoFile() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val downloader =
                downloader {
                    started.complete(Unit)
                    awaitCancellation()
                }
            val result = async { runCatching { downloader.collect("https://example.com/slow.ipa", "slow.ipa") } }

            withTimeout(5_000) { started.await() }
            assertTrue(downloader.cancel("slow.ipa"))

            val outcome = withTimeout(5_000) { result.await() }
            assertTrue(outcome.exceptionOrNull() is IOException)
            assertEquals(emptyList(), filesInDir())
        }

    @Test
    fun cancelOfUnknownNameReturnsFalse() =
        runBlocking {
            assertEquals(false, downloader { respond("x", HttpStatusCode.OK) }.cancel("nothing.ipa"))
        }
}
