package zed.rainxch.githubstore

import kotlinx.coroutines.CoroutineScope
import org.koin.mp.KoinPlatform
import zed.rainxch.core.data.network.ProxyManager
import zed.rainxch.core.domain.repository.ProxyRepository
import zed.rainxch.githubstore.app.di.initKoin

private val started: Unit by lazy {
    initKoin()
    val koin = KoinPlatform.getKoin()
    ProxyManager.bootstrap(
        repository = koin.get<ProxyRepository>(),
        appScope = koin.get<CoroutineScope>(),
    )
}

internal fun ensureAppStarted() = started
