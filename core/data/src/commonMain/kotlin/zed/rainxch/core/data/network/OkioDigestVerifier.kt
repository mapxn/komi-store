package zed.rainxch.core.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import zed.rainxch.core.data.utils.FileDigest
import zed.rainxch.core.domain.network.DigestVerifier

class OkioDigestVerifier(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DigestVerifier {
    override suspend fun verify(
        filePath: String,
        expectedDigest: String,
    ): String? =
        withContext(Dispatchers.IO) {
            if (!fileSystem.exists(filePath.toPath())) return@withContext "File not found: $filePath"
            val expected = expectedDigest.removePrefix("sha256:").lowercase()
            val actual =
                runCatching { FileDigest.sha256Hex(filePath, fileSystem) }
                    .getOrElse { return@withContext "Digest computation failed: ${it.message}" }
            if (actual == expected) null else "Digest mismatch (expected $expected, got $actual)"
        }

    override suspend fun computeSha256(filePath: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!fileSystem.exists(filePath.toPath())) return@runCatching null
                "sha256:" + FileDigest.sha256Hex(filePath, fileSystem)
            }.getOrNull()
        }
}
