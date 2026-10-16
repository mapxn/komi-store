package zed.rainxch.core.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSystemTimeZoneDidChangeNotification

@Composable
actual fun ObserveTimeZoneChanges() {
    DisposableEffect(Unit) {
        val observer =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = NSSystemTimeZoneDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                TimeZoneChangeSignal.onTimeZoneChanged()
            }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
}
