package zed.rainxch.auth.data.crypto

import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalEncodingApi::class)
class PkceGeneratorTest {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun challengeIsBase64UrlSha256OfVerifier() {
        val triplet = PkceGenerator.generate()
        val expected =
            b64.encode(MessageDigest.getInstance("SHA-256").digest(triplet.codeVerifier.encodeToByteArray()))
        assertEquals(expected, triplet.codeChallenge)
    }

    @Test
    fun stateAndVerifierHaveExpectedLengths() {
        val triplet = PkceGenerator.generate()
        assertEquals(43, triplet.state.length)
        assertEquals(64, triplet.codeVerifier.length)
    }

    @Test
    fun consecutiveValuesDiffer() {
        val first = PkceGenerator.generate()
        val second = PkceGenerator.generate()
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.codeVerifier, second.codeVerifier)
    }
}
