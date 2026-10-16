package zed.rainxch.core.data.utils

import platform.UIKit.UIPasteboard
import zed.rainxch.core.domain.helpers.ClipboardHelper

class IosClipboardHelper : ClipboardHelper {
    override fun copy(
        label: String,
        text: String,
    ) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun getText(): String? = UIPasteboard.generalPasteboard.string
}
