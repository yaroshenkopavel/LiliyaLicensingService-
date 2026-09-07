package pro.liliya.licensing.postgres

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import pro.liliya.licensing.issuer.DecisionCandidate
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.DecisionTransactionFailure
import pro.liliya.licensing.issuer.DecisionTransactionPort
import pro.liliya.licensing.issuer.DecisionTransactionResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningKeyReference

data class PersistedDecisionRecord(
    val scope: DecisionScope,
    val state: DecisionState,
    val envelope: SignedLicenseEnvelope
)

/**
 * PostgreSQL-backed authoritative replay/revocation transaction owner.
 *
 * One transaction-scoped advisory lock serializes every decision for the exact subject/product
 * scope. The state row and the signed result lineage commit atomically in the same transaction.
 */
class PostgreSqlDecisionTransactionPort(
    private val dataSource: DataSource
) : DecisionTransactionPort {

    fun initializeSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS licensing_decision_state (
                        subject TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        replay_sequence BIGINT NOT NULL CHECK (replay_sequence >= 0),
                        revocation_epoch BIGINT NOT NULL CHECK (revocation_epoch >= 0),
                        envelope_schema_version BIGINT NOT NULL CHECK (envelope_schema_version > 0),
                        algorithm TEXT NOT NULL CHECK (length(algorithm) > 0),
                        signing_key_reference TEXT NOT NULL CHECK (length(signing_key_reference) > 0),
                        canonical_payload BYTEA NOT NULL CHECK (octet_length(canonical_payload) > 0),
                        signature BYTEA NOT NULL CHECK (octet_length(signature) > 0),
                        PRIMARY KEY (subject, product_id)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    override fun transact(
        scope: DecisionScope,
        block: (DecisionState?) -> DecisionCandidate?
    ): DecisionTransactionResult {
        return try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                try {
                    acquireScopeLock(connection, scope)
                    val current = loadLocked(connection, scope)
                    val candidate = block(current?.state)
                        ?: return rollbackRejected(connection, DecisionTransactionFailure.REJECTED)

                    if (!isMonotonic(current?.state, candidate.nextState)) {
                        return rollbackRejected(connection, DecisionTransactionFailure.CONFLICT)
                    }

                    val persisted = if (current == null) {
                        insert(connection, scope, candidate)
                    } else {
                        updateExact(connection, scope, current.state, candidate)
                    }

                    if (!persisted) {
                        return rollbackRejected(connection, DecisionTransactionFailure.CONFLICT)
                    }

                    connection.commit()
                    DecisionTransactionResult.Committed(candidate.nextState, candidate.envelope)
                } catch (_: SQLException) {
                    safeRollback(connection)
                    DecisionTransactionResult.Rejected(DecisionTransactionFailure.INTERNAL_FAILURE)
                } catch (_: RuntimeException) {
                    safeRollback(connection)
                    DecisionTransactionResult.Rejected(DecisionTransactionFailure.INTERNAL_FAILURE)
                }
            }
        } catch (_: SQLException) {
            DecisionTransactionResult.Rejected(DecisionTransactionFailure.INTERNAL_FAILURE)
        }
    }

    fun inspect(scope: DecisionScope): PersistedDecisionRecord? =
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = true
                load(connection, scope)
            }
        } catch (_: SQLException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    fun deleteForTest(scope: DecisionScope) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM licensing_decision_state WHERE subject = ? AND product_id = ?"
            ).use { statement ->
                statement.setString(1, scope.subject)
                statement.setString(2, scope.productId)
                statement.executeUpdate()
            }
        }
    }

    private fun acquireScopeLock(connection: Connection, scope: DecisionScope) {
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))"
        ).use { statement ->
            statement.setString(1, scope.subject)
            statement.setString(2, scope.productId)
            statement.executeQuery().use { result ->
                check(result.next()) { "advisory lock query returned no row" }
            }
        }
    }

    private fun loadLocked(
        connection: Connection,
        scope: DecisionScope
    ): PersistedDecisionRecord? =
        load(connection, scope, forUpdate = true)

    private fun load(
        connection: Connection,
        scope: DecisionScope,
        forUpdate: Boolean = false
    ): PersistedDecisionRecord? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """
            SELECT replay_sequence, revocation_epoch, envelope_schema_version, algorithm,
                   signing_key_reference, canonical_payload, signature
            FROM licensing_decision_state
            WHERE subject = ? AND product_id = ?$suffix
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, scope.subject)
            statement.setString(2, scope.productId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null

                val replay = result.getLong("replay_sequence")
                val revocation = result.getLong("revocation_epoch")
                val schemaVersion = result.getLong("envelope_schema_version")
                val algorithm = result.getString("algorithm")
                val keyReference = result.getString("signing_key_reference")
                val payload = result.getBytes("canonical_payload")
                val signature = result.getBytes("signature")

                if (
                    replay < 0L ||
                    revocation < 0L ||
                    schemaVersion <= 0L ||
                    algorithm.isNullOrBlank() ||
                    keyReference.isNullOrBlank() ||
                    payload == null || payload.isEmpty() ||
                    signature == null || signature.isEmpty()
                ) {
                    throw IllegalStateException("invalid persisted decision record")
                }

                return PersistedDecisionRecord(
                    scope = scope,
                    state = DecisionState(replay, revocation),
                    envelope = SignedLicenseEnvelope(
                        pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion(schemaVersion),
                        pro.liliya.licensing.signing.SigningAlgorithm(algorithm),
                        SigningKeyReference(keyReference),
                        payload,
                        signature
                    )
                )
            }
        }
    }

    private fun insert(
        connection: Connection,
        scope: DecisionScope,
        candidate: DecisionCandidate
    ): Boolean =
        connection.prepareStatement(
            """
            INSERT INTO licensing_decision_state(
                subject, product_id, replay_sequence, revocation_epoch,
                envelope_schema_version, algorithm, signing_key_reference,
                canonical_payload, signature
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (subject, product_id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            bindCandidate(statement, scope, candidate)
            statement.executeUpdate() == 1
        }

    private fun updateExact(
        connection: Connection,
        scope: DecisionScope,
        current: DecisionState,
        candidate: DecisionCandidate
    ): Boolean =
        connection.prepareStatement(
            """
            UPDATE licensing_decision_state
            SET replay_sequence = ?,
                revocation_epoch = ?,
                envelope_schema_version = ?,
                algorithm = ?,
                signing_key_reference = ?,
                canonical_payload = ?,
                signature = ?
            WHERE subject = ?
              AND product_id = ?
              AND replay_sequence = ?
              AND revocation_epoch = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, candidate.nextState.replaySequence)
            statement.setLong(2, candidate.nextState.revocationEpoch)
            statement.setLong(3, candidate.envelope.schemaVersion.value)
            statement.setString(4, candidate.envelope.algorithm.value)
            statement.setString(5, candidate.envelope.keyReference.value)
            statement.setBytes(6, candidate.envelope.copyCanonicalPayload())
            statement.setBytes(7, candidate.envelope.copySignature())
            statement.setString(8, scope.subject)
            statement.setString(9, scope.productId)
            statement.setLong(10, current.replaySequence)
            statement.setLong(11, current.revocationEpoch)
            statement.executeUpdate() == 1
        }

    private fun bindCandidate(
        statement: java.sql.PreparedStatement,
        scope: DecisionScope,
        candidate: DecisionCandidate
    ) {
        statement.setString(1, scope.subject)
        statement.setString(2, scope.productId)
        statement.setLong(3, candidate.nextState.replaySequence)
        statement.setLong(4, candidate.nextState.revocationEpoch)
        statement.setLong(5, candidate.envelope.schemaVersion.value)
        statement.setString(6, candidate.envelope.algorithm.value)
        statement.setString(7, candidate.envelope.keyReference.value)
        statement.setBytes(8, candidate.envelope.copyCanonicalPayload())
        statement.setBytes(9, candidate.envelope.copySignature())
    }

    private fun isMonotonic(current: DecisionState?, next: DecisionState): Boolean =
        if (current == null) {
            next.replaySequence >= 0L && next.revocationEpoch >= 0L
        } else {
            next.replaySequence > current.replaySequence &&
                next.revocationEpoch >= current.revocationEpoch
        }

    private fun rollbackRejected(
        connection: Connection,
        failure: DecisionTransactionFailure
    ): DecisionTransactionResult.Rejected {
        safeRollback(connection)
        return DecisionTransactionResult.Rejected(failure)
    }

    private fun safeRollback(connection: Connection) {
        try {
            connection.rollback()
        } catch (_: SQLException) {
            // The transaction is already fail-closed; rollback failure is not converted to success.
        }
    }
}
