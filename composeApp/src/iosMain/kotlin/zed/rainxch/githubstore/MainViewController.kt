package zed.rainxch.githubstore

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    ensureAppStarted()
    return ComposeUIViewController {
        val deepLink by DeepLinkBridge.pending.collectAsState()
        App(
            deepLinkUri = deepLink,
            onDeepLinkConsumed = { DeepLinkBridge.consume() },
        )
    }
}
