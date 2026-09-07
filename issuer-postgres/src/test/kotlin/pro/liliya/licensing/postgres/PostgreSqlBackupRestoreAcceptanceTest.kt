package pro.liliya.licensing.postgres

import java.sql.SQLException
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
import pro.liliya.licensing.issuer.DecisionTransactionResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

/**
 * S7.5 cross-process fixture.
 *
 * Individual methods are intentionally invoked by the external backup/restore acceptance harness
 * in separate Gradle processes. The database is the durable boundary between those invocations.
 */
class PostgreSqlBackupRestoreAcceptanceTest {
    private val scope = DecisionScope(
        subject = "s7-5-private-fixture-subject",
        productId = "liliya-s7-5"
    )

    @Test
    fun seed_authoritative_state_for_backup() {
        val port = PostgreSqlDecisionTransactionPort(adminDataSource())
        port.initializeSchema()
        port.deleteForTest(scope)

        val committed = port.transact(scope) { current ->
            assertEquals(null, current)
            DecisionCandidate(
                nextState = DecisionState(
                    replaySequence = 4,
                    revocationEpoch = 9
                ),
                envelope = envelope(4)
            )
        }

        assertIs<DecisionTransactionResult.Committed>(committed)
        val persisted = assertNotNull(port.inspect(scope))
        assertEquals(DecisionState(4, 9), persisted.state)
        assertEnvelope(4, persisted.envelope)
    }

    @Test
    fun restored_authoritative_state_is_exact_and_runtime_can_advance_monotonically() {
        val port = PostgreSqlDecisionTransactionPort(runtimeDataSource())

        val restored = assertNotNull(port.inspect(scope))
        assertEquals(DecisionState(4, 9), restored.state)
        assertEnvelope(4, restored.envelope)

        val advanced = port.transact(scope) { current ->
            assertEquals(DecisionState(4, 9), current)
            DecisionCandidate(
                nextState = DecisionState(
                    replaySequence = 5,
                    revocationEpoch = 9
                ),
                envelope = envelope(5)
            )
        }

        assertIs<DecisionTransactionResult.Committed>(advanced)
        val after = assertNotNull(port.inspect(scope))
        assertEquals(DecisionState(5, 9), after.state)
        assertEnvelope(5, after.envelope)
    }

    @Test
    fun runtime_role_has_no_schema_creation_privilege() {
        runtimeDataSource().connection.use { connection ->
            assertFailsWith<SQLException> {
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE s7_5_runtime_must_not_create_schema_objects(id INT)"
                    )
                }
            }
        }
    }

    private fun assertEnvelope(
        sequence: Long,
        envelope: SignedLicenseEnvelope
    ) {
        assertEquals(1L, envelope.schemaVersion.value)
        assertEquals("S7-5-TEST", envelope.algorithm.value)
        assertEquals(
            SigningKeyReference("s7-5-test-key-v1"),
            envelope.keyReference
        )
        assertContentEquals(
            "s7-5-payload-$sequence".encodeToByteArray(),
            envelope.copyCanonicalPayload()
        )
        assertContentEquals(
            "s7-5-signature-$sequence".encodeToByteArray(),
            envelope.copySignature()
        )
    }

    private fun envelope(sequence: Long): SignedLicenseEnvelope =
        SignedLicenseEnvelope(
            schemaVersion = SigningEnvelopeSchemaVersion(1),
            algorithm = SigningAlgorithm("S7-5-TEST"),
            keyReference = SigningKeyReference("s7-5-test-key-v1"),
            canonicalPayload = "s7-5-payload-$sequence".encodeToByteArray(),
            signature = "s7-5-signature-$sequence".encodeToByteArray()
        )

    private fun adminDataSource(): PGSimpleDataSource =
        dataSource(
            url = requiredEnv("S7_5_POSTGRES_URL"),
            user = requiredEnv("S7_5_POSTGRES_ADMIN_USER"),
            password = requiredEnv("S7_5_POSTGRES_ADMIN_PASSWORD")
        )

    private fun runtimeDataSource(): PGSimpleDataSource =
        dataSource(
            url = requiredEnv("S7_5_POSTGRES_URL"),
            user = requiredEnv("S7_5_POSTGRES_RUNTIME_USER"),
            password = requiredEnv("S7_5_POSTGRES_RUNTIME_PASSWORD")
        )

    private fun dataSource(
        url: String,
        user: String,
        password: String
    ): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(url)
            this.user = user
            this.password = password
        }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("missing S7.5 acceptance environment")
}
