package zed.rainxch.core.domain.system

import zed.rainxch.core.domain.model.installation.InstalledApp

interface DownloadedFileStore {
    fun isFilePresent(app: InstalledApp): Boolean
}
