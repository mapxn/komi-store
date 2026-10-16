package zed.rainxch.core.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.Url
import zed.rainxch.core.domain.model.settings.ProxyConfig

actual fun createPlatformHttpClient(proxyConfig: ProxyConfig): HttpClient =
    HttpClient(Darwin) {
        engine {
            proxy =
                when (proxyConfig) {
                    is ProxyConfig.None, is ProxyConfig.System -> null
                    is ProxyConfig.Http -> ProxyBuilder.http(Url("http://${proxyConfig.host}:${proxyConfig.port}"))
                    is ProxyConfig.Socks -> ProxyBuilder.socks(proxyConfig.host, proxyConfig.port)
                }
        }
    }
