package pro.liliya.licensing.activation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActivationCodeContractTest {
    @Test
    fun generated_code_is_canonical_parseable_and_redacted() {
        val code = SecureActivationCodeGenerator().generate()
        try {
            val text = code.useText { it }

            assertTrue(text.matches(Regex("^LIL(?:-[0-9A-F]{4}){8}$")))
            assertEquals("ActivationCode(<redacted>)", code.toString())

            val parsed = ActivationCode.parse(text.lowercase())
            requireNotNull(parsed)
            parsed.close()
        } finally {
            code.close()
        }
    }

    @Test
    fun hash_is_stable_for_equivalent_user_input_but_does_not_render_secret() {
        val canonical = ActivationCode.parse("LIL-0011-2233-4455-6677-8899-AABB-CCDD-EEFF")
            ?: error("fixture code invalid")
        val loose = ActivationCode.parse(" lil 0011 2233 4455 6677 8899 aabb ccdd eeff ")
            ?: error("loose fixture code invalid")

        canonical.use {
            loose.use {
                assertEquals(
                    ActivationCodeHasher.sha256(canonical),
                    ActivationCodeHasher.sha256(loose)
                )
                assertEquals(
                    "ActivationCodeHash(<redacted>)",
                    ActivationCodeHasher.sha256(canonical).toString()
                )
            }
        }
    }

    @Test
    fun provisioning_returns_plaintext_once_but_store_receives_only_hash() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        val expected = ActivationCode.parse(
            "LIL-1111-2222-3333-4444-5555-6666-7777-8888"
        ) ?: error("fixture code invalid")

        var storedGrant: ActivationGrant? = null
        val store = object : ActivationGrantStore {
            override fun create(grant: ActivationGrant): Boolean {
                storedGrant = grant
                return true
            }

            override fun prepare(
                codeHash: ActivationCodeHash,
                requestId: String,
                now: Instant
            ): ActivationPreparationResult = error("not used")

            override fun complete(
                prepared: ActivationPreparedGrant,
                responseBody: ByteArray,
                now: Instant
            ): Boolean = error("not used")
        }

        val service = ActivationProvisioningService(
            store = store,
            generator = ActivationCodeGenerator {
                ActivationCode.parse(
                    "LIL-1111-2222-3333-4444-5555-6666-7777-8888"
                ) ?: error("fixture code invalid")
            }
        )

        val result = assertIs<ActivationProvisioningResult.Created>(
            service.create(
                subject = "device-subject",
                productId = "liliya-pro",
                expiresAt = now.plusSeconds(900),
                now = now
            )
        )

        try {
            assertEquals(
                "LIL-1111-2222-3333-4444-5555-6666-7777-8888",
                result.code.useText { it }
            )
            val grant = requireNotNull(storedGrant)
            assertEquals("device-subject", grant.subject)
            assertEquals("liliya-pro", grant.productId)
            assertEquals(
                ActivationCodeHasher.sha256(expected),
                grant.codeHash
            )
            assertFalse(grant.toString().contains("1111"))
        } finally {
            expected.close()
            result.code.close()
        }
    }

    @Test
    fun invalid_user_code_fails_closed_without_store_call() {
        var calls = 0
        val store = object : ActivationGrantStore {
            override fun create(grant: ActivationGrant): Boolean = error("not used")

            override fun prepare(
                codeHash: ActivationCodeHash,
                requestId: String,
                now: Instant
            ): ActivationPreparationResult {
                calls += 1
                return ActivationPreparationResult.Invalid
            }

            override fun complete(
                prepared: ActivationPreparedGrant,
                responseBody: ByteArray,
                now: Instant
            ): Boolean = error("not used")
        }

        val result = ActivationRedemptionService(store).prepare(
            rawCode = "not-a-liliya-code",
            requestId = "activation-request-invalid"
        )

        assertIs<ActivationPreparationResult.Invalid>(result)
        assertEquals(0, calls)
    }

    @Test
    fun independent_generated_codes_are_not_equal() {
        val generator = SecureActivationCodeGenerator()
        val first = generator.generate()
        val second = generator.generate()

        try {
            assertNotEquals(
                ActivationCodeHasher.sha256(first),
                ActivationCodeHasher.sha256(second)
            )
        } finally {
            first.close()
            second.close()
        }
    }
}
