package zed.rainxch.core.domain.system

interface LocalFileSystem {
    fun exists(path: String): Boolean

    fun size(path: String): Long

    fun delete(path: String): Boolean
}
