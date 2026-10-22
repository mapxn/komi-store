package zed.rainxch.core.data.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import zed.rainxch.core.data.packages.PackageArchiveReader
import zed.rainxch.core.domain.model.apk.ApkPackageInfo
import zed.rainxch.core.domain.system.InstallerInfoExtractor

class IosInstallerInfoExtractor(
    private val reader: PackageArchiveReader = PackageArchiveReader(),
) : InstallerInfoExtractor {
    override suspend fun extractPackageInfo(filePath: String): ApkPackageInfo? = withContext(Dispatchers.IO) { reader.read(filePath) }
}
