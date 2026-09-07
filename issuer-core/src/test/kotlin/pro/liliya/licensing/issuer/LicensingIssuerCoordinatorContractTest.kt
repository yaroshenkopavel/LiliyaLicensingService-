package pro.liliya.licensing.issuer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseRequestValidator
import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.protocol.LicenseOperation
import pro.liliya.licensing.signing.LicenseEnvelopeSigner
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningResult

class LicensingIssuerCoordinatorContractTest {
    @Test
    fun ineligible_source_never_reaches_signer() {
        var signerCalls = 0
        val coordinator = coordinator(
            source = EntitlementSourcePort {
                EntitlementSourceResult.Ineligible(LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE)
            },
            signer = LicenseEnvelopeSigner { _, _ ->
                signerCalls++
                error("must not sign")
            },
            transactions = singleCommitTransactions()
        )

        val result = coordinator.process(request())

        val rejected = assertIs<LicensingIssuerResult.Rejected>(result)
        assertEquals(LicenseServiceFailure.SUBJECT_NOT_ELIGIBLE, rejected.reason)
        assertEquals(0, signerCalls)
    }

    @Test
    fun source_failure_never_mints_entitlement() {
        var signerCalls = 0
        val coordinator = coordinator(
            source = EntitlementSourcePort {
                EntitlementSourceResult.Failed(LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE)
            },
            signer = LicenseEnvelopeSigner { _, _ ->
                signerCalls++
                error("must not sign")
            },
            transactions = singleCommitTransactions()
        )

        val rejected = assertIs<LicensingIssuerResult.Rejected>(coordinator.process(request()))
        assertEquals(LicenseServiceFailure.ENTITLEMENT_SOURCE_UNAVAILABLE, rejected.reason)
        assertEquals(0, signerCalls)
    }

    @Test
    fun eligible_source_uses_authoritative_record_not_request_as_entitlement_facts() {
        val source = record()
        var signedPayloads = 0
        val coordinator = coordinator(
            source = EntitlementSourcePort { EntitlementSourceResult.Eligible(source) },
            signer = LicenseEnvelopeSigner { payload, key ->
                signedPayloads++
                SigningResult.Signed(
                    SignedLicenseEnvelope(
                        pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion(1),
                        pro.liliya.licensing.signing.SigningAlgorithm("TEST-ED25519"),
                        key,
                        payload,
                        byteArrayOf(1)
                    )
                )
            },
            transactions = singleCommitTransactions()
        )

        val result = assertIs<LicensingIssuerResult.Issued>(
            coordinator.process(
                request().copy(
                    productId = "request-lookup-product",
                    subjectReference = "request-lookup-subject"
                )
            )
        )

        assertEquals(1, signedPayloads)
        assertEquals(0L, result.state.replaySequence)
        assertEquals(source.revocationEpoch, result.state.revocationEpoch)
    }

    @Test
    fun product_ineligible_request_never_reaches_signer() {
        var signerCalls = 0
        val coordinator = coordinator(
            source = EntitlementSourcePort {
                EntitlementSourceResult.Ineligible(LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE)
            },
            signer = LicenseEnvelopeSigner { _, _ ->
                signerCalls++
                error("must not sign")
            },
            transactions = singleCommitTransactions()
        )

        val rejected = assertIs<LicensingIssuerResult.Rejected>(
            coordinator.process(request())
        )

        assertEquals(LicenseServiceFailure.PRODUCT_NOT_ELIGIBLE, rejected.reason)
        assertEquals(0, signerCalls)
    }

    @Test
    fun successful_refresh_issues_new_signed_evidence_and_advances_replay() {
        val signedPayloads = mutableListOf<ByteArray>()
        val transactions = statefulTransactions()
        val coordinator = coordinator(
            source = EntitlementSourcePort {
                EntitlementSourceResult.Eligible(record())
            },
            signer = LicenseEnvelopeSigner { payload, key ->
                signedPayloads += payload.copyOf()
                SigningResult.Signed(
                    SignedLicenseEnvelope(
                        pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion(1),
                        pro.liliya.licensing.signing.SigningAlgorithm("TEST-ED25519"),
                        key,
                        payload,
                        byteArrayOf((signedPayloads.size + 1).toByte())
                    )
                )
            },
            transactions = transactions
        )

        val first = assertIs<LicensingIssuerResult.Issued>(
            coordinator.process(request())
        )
        val refreshed = assertIs<LicensingIssuerResult.Issued>(
            coordinator.process(
                request().copy(
                    operation = LicenseOperation.REFRESH,
                    requestId = "refresh-attempt-001"
                )
            )
        )

        assertEquals(0L, first.state.replaySequence)
        assertEquals(1L, refreshed.state.replaySequence)
        assertEquals(2, signedPayloads.size)
        assertFalse(signedPayloads[0].contentEquals(signedPayloads[1]))
    }

    @Test
    fun rejected_refresh_never_reaches_signer_or_commits_new_lineage() {
        var signerCalls = 0
        var transactionCalls = 0
        val coordinator = coordinator(
            source = EntitlementSourcePort { request ->
                if (request.operation == LicenseOperation.REFRESH) {
                    EntitlementSourceResult.Ineligible(LicenseServiceFailure.REFRESH_REJECTED)
                } else {
                    EntitlementSourceResult.Eligible(record())
                }
            },
            signer = LicenseEnvelopeSigner { payload, key ->
                signerCalls++
                SigningResult.Signed(
                    SignedLicenseEnvelope(
                        pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion(1),
                        pro.liliya.licensing.signing.SigningAlgorithm("TEST-ED25519"),
                        key,
                        payload,
                        byteArrayOf(1)
                    )
                )
            },
            transactions = DecisionTransactionPort { _, block ->
                transactionCalls++
                val candidate = block(null)
                    ?: return@DecisionTransactionPort DecisionTransactionResult.Rejected(
                        DecisionTransactionFailure.REJECTED
                    )
                DecisionTransactionResult.Committed(candidate.nextState, candidate.envelope)
            }
        )

        val rejected = assertIs<LicensingIssuerResult.Rejected>(
            coordinator.process(
                request().copy(
                    operation = LicenseOperation.REFRESH,
                    requestId = "old-license-is-not-authority"
                )
            )
        )

        assertEquals(LicenseServiceFailure.REFRESH_REJECTED, rejected.reason)
        assertEquals(0, signerCalls)
        assertEquals(0, transactionCalls)
    }

    private fun coordinator(
        source: EntitlementSourcePort,
        signer: LicenseEnvelopeSigner,
        transactions: DecisionTransactionPort
    ) = LicensingIssuerCoordinator(
        validator = LicenseRequestValidator(LicenseProtocolVersion(1)),
        source = source,
        transactions = transactions,
        signing = LicenseSigningComposition(signer)
    )

    private fun statefulTransactions(): DecisionTransactionPort {
        var state: DecisionState? = null
        return DecisionTransactionPort { _, block ->
            val candidate = block(state)
                ?: return@DecisionTransactionPort DecisionTransactionResult.Rejected(
                    DecisionTransactionFailure.REJECTED
                )
            state = candidate.nextState
            DecisionTransactionResult.Committed(candidate.nextState, candidate.envelope)
        }
    }

    private fun singleCommitTransactions() = DecisionTransactionPort { _, block ->
        val candidate = block(null)
            ?: return@DecisionTransactionPort DecisionTransactionResult.Rejected(
                DecisionTransactionFailure.REJECTED
            )
        DecisionTransactionResult.Committed(candidate.nextState, candidate.envelope)
    }

    private fun request() = LicenseServiceRequest(
        protocolVersion = LicenseProtocolVersion(1),
        operation = LicenseOperation.ISSUE,
        productId = "lookup-product",
        subjectReference = "lookup-subject"
    )

    private fun record() = EntitlementSourceRecord(
        licenseId = "lic-001",
        subject = "authoritative-subject",
        productId = "authoritative-product",
        features = setOf("core"),
        version = 1,
        signingKeyId = "test-key-1",
        issuedAt = Instant.parse("2026-09-07T08:00:00Z"),
        notBefore = Instant.parse("2026-09-07T08:00:00Z"),
        expiresAt = Instant.parse("2026-10-07T08:00:00Z"),
        offlineLeaseUntil = Instant.parse("2026-09-14T08:00:00Z"),
        revocationEpoch = 3
    )
}
