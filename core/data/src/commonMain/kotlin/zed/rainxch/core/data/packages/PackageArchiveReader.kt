package zed.rainxch.core.data.packages

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use
import zed.rainxch.core.domain.model.apk.ApkPackageInfo

class PackageArchiveReader(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    fun read(filePath: String): ApkPackageInfo? =
        when (filePath.substringAfterLast('.', "").lowercase()) {
            "apk" -> readApk(filePath)
            "ipa" -> readIpa(filePath)
            else -> null
        }

    private fun readApk(filePath: String): ApkPackageInfo? =
        withZip(filePath) { zip ->
            val bytes = zip.readEntry("/AndroidManifest.xml".toPath()) ?: return@withZip null
            val manifest = AndroidManifestParser.parse(bytes) ?: return@withZip null
            ApkPackageInfo(
                packageName = manifest.packageName,
                versionName = manifest.versionName ?: UNKNOWN_VERSION,
                versionCode = manifest.versionCode,
                appName = manifest.label.orEmpty(),
                signingFingerprint = null,
            )
        }

    private fun readIpa(filePath: String): ApkPackageInfo? =
        withZip(filePath) { zip ->
            val appDirectory =
                zip
                    .listOrNull("/Payload".toPath())
                    ?.filter { it.name.endsWith(".app", ignoreCase = true) }
                    ?.minByOrNull { it.name }
                    ?: return@withZip null
            val bytes = zip.readEntry(appDirectory / "Info.plist") ?: return@withZip null
            val plist = PropertyListParser.parseTopLevelStrings(bytes) ?: return@withZip null

            val identifier = plist["CFBundleIdentifier"]?.takeIf { it.isNotBlank() } ?: return@withZip null
            val buildNumber = plist["CFBundleVersion"]
            ApkPackageInfo(
                packageName = identifier,
                versionName = plist["CFBundleShortVersionString"] ?: buildNumber ?: UNKNOWN_VERSION,
                versionCode = buildNumber?.toLongOrNull() ?: 0L,
                appName = plist["CFBundleDisplayName"] ?: plist["CFBundleName"] ?: "",
                signingFingerprint = null,
            )
        }

    private fun <T> withZip(
        filePath: String,
        block: (FileSystem) -> T?,
    ): T? =
        try {
            val zipPath = filePath.toPath()
            if (fileSystem.exists(zipPath)) block(fileSystem.openZip(zipPath)) else null
        } catch (_: Exception) {
            null
        }

    private fun FileSystem.readEntry(path: Path): ByteArray? {
        val metadata = metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile) return null
        if ((metadata.size ?: 0L) > MAX_ENTRY_BYTES) return null
        return source(path).buffer().use { it.readByteArray() }
    }

    private companion object {
        const val UNKNOWN_VERSION = "unknown"
        const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    }
}
