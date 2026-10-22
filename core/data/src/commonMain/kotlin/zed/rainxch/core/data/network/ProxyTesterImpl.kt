package zed.rainxch.core.data.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.util.network.UnresolvedAddressException
import zed.rainxch.core.domain.model.settings.ProxyConfig
import zed.rainxch.core.domain.network.ProxyTestOutcome
import zed.rainxch.core.domain.network.ProxyTester
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

class ProxyTesterImpl : ProxyTester {
    override suspend fun test(config: ProxyConfig): ProxyTestOutcome = test(config, TEST_URL)

    override suspend fun test(
        config: ProxyConfig,
        url: String,
    ): ProxyTestOutcome {
        val client =
            createPlatformHttpClient(config).config {
                install(HttpTimeout) {
                    requestTimeoutMillis = TEST_TIMEOUT_MS
                    connectTimeoutMillis = TEST_TIMEOUT_MS
                    socketTimeoutMillis = TEST_TIMEOUT_MS
                }
                expectSuccess = false
            }

        return try {
            val started = TimeSource.Monotonic.markNow()
            val response: HttpResponse = client.get(url)
            val elapsed = started.elapsedNow().inWholeMilliseconds

            when {
                response.status.value == 407 -> {
                    ProxyTestOutcome.Failure.ProxyAuthRequired
                }

                response.status.value in 200..299 -> {
                    ProxyTestOutcome.Success(latencyMs = elapsed)
                }

                else -> {
                    ProxyTestOutcome.Failure.UnexpectedResponse(response.status.value)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyProxyFailure(e)
        } finally {
            client.close()
        }
    }

    private companion object {
        const val TEST_URL = "https://api.github.com/zen"
        const val TEST_TIMEOUT_MS = 8_000L
    }
}

internal fun classifyProxyFailure(error: Exception): ProxyTestOutcome.Failure =
    when {
        error is HttpRequestTimeoutException || error.hasClassNamed("SocketTimeoutException") -> {
            ProxyTestOutcome.Failure.Timeout
        }

        error is UnresolvedAddressException || error.hasClassNamed("UnknownHostException") -> {
            ProxyTestOutcome.Failure.DnsFailure
        }

        error.hasClassNamed("ConnectException") -> {
            ProxyTestOutcome.Failure.ProxyUnreachable
        }

        else -> {
            ProxyTestOutcome.Failure.Unknown(error.message)
        }
    }

private fun Throwable.hasClassNamed(name: String): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it::class.simpleName == name }
