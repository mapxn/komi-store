package zed.rainxch.core.data.local

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

object IosAppDataPaths {
    fun documentsDir(): String = searchPath(NSDocumentDirectory)

    fun cachesDir(): String = searchPath(NSCachesDirectory)

    fun appDataDir(): String {
        val dir = "${searchPath(NSApplicationSupportDirectory)}/KomiStore"
        FileSystem.SYSTEM.createDirectories(dir.toPath())
        return dir
    }

    private fun searchPath(directory: NSSearchPathDirectory): String =
        NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String
            ?: error("No directory available for $directory")
}
