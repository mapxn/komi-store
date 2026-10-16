package zed.rainxch.core.data.download

import okio.Path
import okio.Path.Companion.toPath
import zed.rainxch.core.domain.model.installation.InstalledApp
import zed.rainxch.core.domain.utils.AssetFileName
import kotlin.random.Random

object DownloadFileName {
    fun sanitize(
        suggested: String?,
        url: String,
    ): String {
        val raw =
            suggested?.takeIf { it.isNotBlank() }
                ?: url
                    .substringAfterLast('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .ifBlank { "asset-${Random.nextLong().toString(16)}" }
        val name =
            raw
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { it.code >= 0x20 && it.code != 0x7F }
                .trim()
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid file name: $raw" }
        return name
    }
}

fun downloadedFilePath(
    downloadsDir: String,
    assetName: String?,
): Path? {
    val name = assetName?.takeIf { it.isNotBlank() } ?: return null
    val safeName =
        try {
            DownloadFileName.sanitize(name, "")
        } catch (_: IllegalArgumentException) {
            return null
        }
    return downloadsDir.toPath() / safeName
}

fun downloadedInstalledFilePath(
    downloadsDir: String,
    app: InstalledApp,
): Path? {
    val raw = app.installedAssetName?.takeIf { it.isNotBlank() } ?: return null
    val scoped =
        AssetFileName.scoped(
            owner = app.repoOwner,
            repo = app.repoName,
            originalName = raw,
        )
    return downloadedFilePath(downloadsDir, scoped)
}
