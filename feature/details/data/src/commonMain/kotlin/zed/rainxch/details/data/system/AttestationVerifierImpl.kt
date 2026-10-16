package zed.rainxch.details.data.system

import kotlinx.coroutines.CancellationException
import zed.rainxch.core.data.utils.FileDigest
import zed.rainxch.core.domain.logging.KomiStoreLogger
import zed.rainxch.details.domain.repository.DetailsRepository
import zed.rainxch.details.domain.system.AttestationVerifier
import zed.rainxch.details.domain.system.VerificationResult

class AttestationVerifierImpl(
    private val detailsRepository: DetailsRepository,
    private val logger: KomiStoreLogger,
) : AttestationVerifier {
    override suspend fun verify(
        owner: String,
        repoName: String,
        filePath: String,
    ): VerificationResult =
        try {
            val digest = FileDigest.sha256Hex(filePath)
            val hasAttestation = detailsRepository.checkAttestations(owner, repoName, digest)
            if (hasAttestation) VerificationResult.Verified else VerificationResult.Unverified
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Attestation check error: ${e.message}")
            VerificationResult.Error(e.message ?: "Unknown error")
        }
}
