package zed.rainxch.core.data.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import zed.rainxch.core.data.download.DownloadFileName
import zed.rainxch.core.domain.helpers.ShareManager

@OptIn(ExperimentalForeignApi::class)
class IosShareManager(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ShareManager {
    override fun shareText(text: String) {
        present(listOf(text))
    }

    override fun shareFile(
        fileName: String,
        content: String,
        mimeType: String,
    ) {
        val path = NSTemporaryDirectory().toPath() / DownloadFileName.sanitize(fileName, "")
        fileSystem.write(path) { writeUtf8(content) }
        present(listOf(NSURL.fileURLWithPath(path.toString())))
    }

    override fun pickFile(
        mimeType: String,
        onResult: (String?) -> Unit,
    ) {
        onResult(null)
    }

    override fun shareExistingFile(filePath: String): Boolean {
        if (!fileSystem.exists(filePath.toPath())) return false
        return present(listOf(NSURL.fileURLWithPath(filePath)))
    }

    private fun present(items: List<Any>): Boolean {
        val top = topViewController() ?: return false
        val controller = UIActivityViewController(activityItems = items, applicationActivities = null)
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            popover.sourceRect =
                top.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        }
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

    private fun topViewController(): UIViewController? {
        val scene =
            UIApplication.sharedApplication.connectedScenes
                .firstOrNull { it is UIWindowScene && it.activationState == UISceneActivationStateForegroundActive }
                as? UIWindowScene
        val window = scene?.keyWindow ?: (scene?.windows?.firstOrNull() as? UIWindow)
        var top = window?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }
}
