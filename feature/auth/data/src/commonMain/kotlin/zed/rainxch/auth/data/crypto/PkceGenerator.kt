package zed.rainxch.auth.data.crypto

import zed.rainxch.core.domain.utils.secureRandomBytes
import zed.rainxch.core.domain.utils.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object PkceGenerator {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun generate(): PkceTriplet {
        val state = b64.encode(secureRandomBytes(STATE_BYTES))
        val codeVerifier = b64.encode(secureRandomBytes(VERIFIER_BYTES))
        val codeChallenge = b64.encode(sha256(codeVerifier.encodeToByteArray()))

        return PkceTriplet(
            state = state,
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
        )
    }

    private const val STATE_BYTES = 32
    private const val VERIFIER_BYTES = 48
}
