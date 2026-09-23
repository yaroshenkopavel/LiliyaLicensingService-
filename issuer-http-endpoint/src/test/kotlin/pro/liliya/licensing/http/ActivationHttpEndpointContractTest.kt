package pro.liliya.licensing.http

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pro.liliya.licensing.activation.ActivationCredentialLookupResult
import pro.liliya.licensing.activation.ActivationGrant
import pro.liliya.licensing.activation.ActivationRandomBytes
import pro.liliya.licensing.activation.ActivationService
import pro.liliya.licensing.activation.ActivationStoreConsumeResult
import pro.liliya.licensing.activation.ActivationStorePort

class ActivationHttpEndpointContractTest {
    @Test
    fun successful_activation_returns_credential_once_without_echoing_code() {
        val code = "ONE-TIME-PRIVATE-CODE"
        val endpoint = ActivationHttpEndpoint(
            ActivationService(
                store = successfulStore(),
                randomBytes = ActivationRandomBytes { size ->
                    ByteArray(size) { 11 }
                }
            )
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationHttpEndpoint.PATH,
                body = """{"activationCode":"$code"}""".encodeToByteArray()
            )
        )

        val body = response.body.toString(Charsets.UTF_8)
        assertEquals(200, response.status)
        assertTrue(""kind":"activated"" in body)
        assertTrue(""productId":"liliya-pro"" in body)
        assertTrue(""credential":" in body)
        assertFalse(code in body)
        assertFalse(code in endpoint.toString())
    }

    @Test
    fun invalid_or_missing_code_is_indistinguishable_and_fails_closed() {
        val endpoint = ActivationHttpEndpoint(
            ActivationService(
                store = rejectingStore(),
                randomBytes = ActivationRandomBytes { size -> ByteArray(size) { 3 } }
            )
        )

        val invalid = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationHttpEndpoint.PATH,
                body = """{"activationCode":"wrong"}""".encodeToByteArray()
            )
        )
        val missing = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationHttpEndpoint.PATH,
                body = """{"other":"value"}""".encodeToByteArray()
            )
        )

        assertEquals(401, invalid.status)
        assertEquals(401, missing.status)
        assertEquals(
            invalid.body.toString(Charsets.UTF_8),
            missing.body.toString(Charsets.UTF_8)
        )
    }

    @Test
    fun activation_store_failure_is_503_without_secret_material() {
        val secret = "PRIVATE-ACTIVATION-CODE"
        val endpoint = ActivationHttpEndpoint(
            ActivationService(
                store = failingStore(),
                randomBytes = ActivationRandomBytes { size -> ByteArray(size) { 5 } }
            )
        )

        val response = endpoint.handle(
            LicenseHttpRequest(
                method = LicenseHttpMethod.POST,
                path = ActivationHttpEndpoint.PATH,
                body = """{"activationCode":"$secret"}""".encodeToByteArray()
            )
        )

        assertEquals(503, response.status)
        assertTrue(response.body.isEmpty())
        assertFalse(secret in response.toString())
    }

    private fun successfulStore() =
        object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = ActivationStoreConsumeResult.Consumed(
                ActivationGrant("subject-private", "liliya-pro")
            )

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Rejected

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }

    private fun rejectingStore() =
        object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = ActivationStoreConsumeResult.Rejected

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Rejected

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }

    private fun failingStore() =
        object : ActivationStorePort {
            override fun consume(
                activationCodeDigest: ByteArray,
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = ActivationStoreConsumeResult.Failed

            override fun lookupCredential(
                clientCredentialDigest: ByteArray
            ) = ActivationCredentialLookupResult.Failed

            override fun revokeCredential(
                clientCredentialDigest: ByteArray,
                now: Instant
            ) = false
        }
}
