package pro.liliya.licensing.postgres

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.issuer.DecisionCandidate
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.DecisionTransactionFailure
import pro.liliya.licensing.issuer.DecisionTransactionResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningKeyReference

class PostgreSqlDecisionTransactionPortIntegrationTest {
    private val scope = DecisionScope("subject-s5-4", "product-s5-4")
    private lateinit var port: PostgreSqlDecisionTransactionPort

    @BeforeTest
    fun setUp() {
        port = PostgreSqlDecisionTransactionPort(dataSource())
        port.initializeSchema()
        port.deleteForTest(scope)
    }

    @AfterTest
    fun tearDown() {
        port.deleteForTest(scope)
    }

    @Test
    fun empty_legacy_schema_is_upgraded_without_fabricating_authoritative_data() {
        recreateLegacySchema(withAuthoritativeRow = false)

        val migrated = PostgreSqlDecisionTransactionPort(dataSource())
        migrated.initializeSchema()

        val committed = migrated.transact(scope) {
            DecisionCandidate(DecisionState(0, 1), envelope(0))
        }

        assertIs<DecisionTransactionResult.Committed>(committed)
        val reopened = assertNotNull(migrated.inspect(scope))
        assertEquals(1L, reopened.envelope.schemaVersion.value)
        assertEquals("TEST-ED25519", reopened.envelope.algorithm.value)
    }

    @Test
    fun populated_legacy_schema_fails_closed_and_requires_explicit_offline_migration() {
        recreateLegacySchema(withAuthoritativeRow = true)

        val failure = assertFailsWith<IllegalStateException> {
            PostgreSqlDecisionTransactionPort(dataSource()).initializeSchema()
        }

        assertTrue("explicit offline migration is required" in failure.message.orEmpty())
        assertEquals(1L, legacyRowCount())
    }

    @Test
    fun first_commit_persists_exact_state_and_signed_result_lineage() {
        val envelope = envelope(0)
        val result = port.transact(scope) { current ->
            assertEquals(null, current)
            DecisionCandidate(DecisionState(0, 3), envelope)
        }

        val committed = assertIs<DecisionTransactionResult.Committed>(result)
        assertEquals(DecisionState(0, 3), committed.state)

        val persisted = assertNotNull(port.inspect(scope))
        assertEquals(DecisionState(0, 3), persisted.state)
        assertEquals(envelope.keyReference, persisted.envelope.keyReference)
        assertContentEquals(
            envelope.copyCanonicalPayload(),
            persisted.envelope.copyCanonicalPayload()
        )
        assertContentEquals(envelope.copySignature(), persisted.envelope.copySignature())
    }

    @Test
    fun fresh_port_instance_reopens_committed_state_and_advances_monotonically() {
        assertIs<DecisionTransactionResult.Committed>(
            port.transact(scope) {
                DecisionCandidate(DecisionState(0, 3), envelope(0))
            }
        )

        val reopened = PostgreSqlDecisionTransactionPort(dataSource())
        reopened.initializeSchema()

        val second = reopened.transact(scope) { current ->
            assertEquals(DecisionState(0, 3), current)
            DecisionCandidate(DecisionState(1, 3), envelope(1))
        }

        assertIs<DecisionTransactionResult.Committed>(second)
        assertEquals(DecisionState(1, 3), reopened.inspect(scope)?.state)
    }

    @Test
    fun lower_revocation_epoch_is_rejected_and_does_not_overwrite_state() {
        assertIs<DecisionTransactionResult.Committed>(
            port.transact(scope) {
                DecisionCandidate(DecisionState(4, 9), envelope(4))
            }
        )

        val result = port.transact(scope) {
            DecisionCandidate(DecisionState(5, 8), envelope(5))
        }

        val rejected = assertIs<DecisionTransactionResult.Rejected>(result)
        assertEquals(DecisionTransactionFailure.CONFLICT, rejected.reason)
        assertEquals(DecisionState(4, 9), port.inspect(scope)?.state)
    }

    @Test
    fun non_advancing_replay_sequence_is_rejected() {
        assertIs<DecisionTransactionResult.Committed>(
            port.transact(scope) {
                DecisionCandidate(DecisionState(7, 2), envelope(7))
            }
        )

        val result = port.transact(scope) {
            DecisionCandidate(DecisionState(7, 2), envelope(8))
        }

        val rejected = assertIs<DecisionTransactionResult.Rejected>(result)
        assertEquals(DecisionTransactionFailure.CONFLICT, rejected.reason)
        assertEquals(DecisionState(7, 2), port.inspect(scope)?.state)
    }

    @Test
    fun rejected_block_publishes_nothing() {
        val result = port.transact(scope) { null }

        val rejected = assertIs<DecisionTransactionResult.Rejected>(result)
        assertEquals(DecisionTransactionFailure.REJECTED, rejected.reason)
        assertEquals(null, port.inspect(scope))
    }

    @Test
    fun concurrent_same_scope_writers_serialize_without_lost_update() {
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val results = java.util.Collections.synchronizedList(
            mutableListOf<DecisionTransactionResult>()
        )

        repeat(workers) {
            Thread {
                ready.countDown()
                start.await(10, TimeUnit.SECONDS)
                results += port.transact(scope) { current ->
                    val nextReplay = (current?.replaySequence ?: -1L) + 1L
                    DecisionCandidate(
                        DecisionState(nextReplay, current?.revocationEpoch ?: 1L),
                        envelope(nextReplay)
                    )
                }
                done.countDown()
            }.start()
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals(workers, results.count { it is DecisionTransactionResult.Committed })
        assertEquals((workers - 1).toLong(), port.inspect(scope)?.state?.replaySequence)
    }

    private fun recreateLegacySchema(withAuthoritativeRow: Boolean) {
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS licensing_decision_state")
                statement.execute(
                    """
                    CREATE TABLE licensing_decision_state (
                        subject TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        replay_sequence BIGINT NOT NULL CHECK (replay_sequence >= 0),
                        revocation_epoch BIGINT NOT NULL CHECK (revocation_epoch >= 0),
                        signing_key_reference TEXT NOT NULL CHECK (length(signing_key_reference) > 0),
                        canonical_payload BYTEA NOT NULL CHECK (octet_length(canonical_payload) > 0),
                        signature BYTEA NOT NULL CHECK (octet_length(signature) > 0),
                        PRIMARY KEY (subject, product_id)
                    )
                    """.trimIndent()
                )
                if (withAuthoritativeRow) {
                    statement.executeUpdate(
                        """
                        INSERT INTO licensing_decision_state(
                            subject, product_id, replay_sequence, revocation_epoch,
                            signing_key_reference, canonical_payload, signature
                        ) VALUES (
                            'subject-s5-4', 'product-s5-4', 4, 9,
                            'legacy-key', decode('01', 'hex'), decode('02', 'hex')
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    private fun legacyRowCount(): Long =
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM licensing_decision_state"
                ).use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }
        }

    private fun envelope(sequence: Long) = SignedLicenseEnvelope(
        pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion(1),
        pro.liliya.licensing.signing.SigningAlgorithm("TEST-ED25519"),
        SigningKeyReference("test-key-s5-4"),
        "payload-$sequence".encodeToByteArray(),
        "signature-$sequence".encodeToByteArray()
    )

    private fun dataSource(): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(
                System.getenv("TEST_POSTGRES_URL")
                    ?: "jdbc:postgresql://localhost:5432/liliya_licensing_test"
            )
            user = System.getenv("TEST_POSTGRES_USER") ?: "liliya"
            password = System.getenv("TEST_POSTGRES_PASSWORD") ?: "liliya-test"
        }
}
