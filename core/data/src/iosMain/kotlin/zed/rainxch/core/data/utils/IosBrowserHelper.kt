package zed.rainxch.core.data.utils

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import zed.rainxch.core.domain.helpers.BrowserHelper

class IosBrowserHelper : BrowserHelper {
    override fun openUrl(
        url: String,
        onFailure: (error: String) -> Unit,
    ) {
        val target = NSURL.URLWithString(url)
        if (target == null) {
            onFailure("Invalid URL: $url")
            return
        }
        UIApplication.sharedApplication.openURL(
            target,
            options = emptyMap<Any?, Any?>(),
            completionHandler = { opened ->
                if (!opened) onFailure("Could not open $url")
            },
        )
    }
}
