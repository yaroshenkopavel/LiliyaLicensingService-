package pro.liliya.licensing.postgres

import java.time.Instant
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.issuer.DecisionCandidate
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState
import pro.liliya.licensing.issuer.DecisionTransactionResult
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningAlgorithm
import pro.liliya.licensing.signing.SigningEnvelopeSchemaVersion
import pro.liliya.licensing.signing.SigningKeyReference

class PostgreSqlLicenseRevocationServiceStateIntegrationTest {
    private val subject = "subject-license-revoke-it"
    private val product = "liliya-pro"
    private val scope = DecisionScope(subject, product)
    private lateinit var dataSource: DataSource
    private lateinit var decisions: PostgreSqlDecisionTransactionPort

    @BeforeTest
    fun setUp() {
        dataSource = dataSource()
        resetTables()
        PostgreSqlEntitlementSchema.initialize(dataSource)
        PostgreSqlActivationRedemptionSchema.initialize(dataSource)
        decisions = PostgreSqlDecisionTransactionPort(dataSource)
        decisions.initializeSchema()
    }

    @AfterTest
    fun tearDown() {
        resetTables()
    }

    @Test
    fun revoke_license_advances_service_state_epoch_without_advancing_replay() {
        seedEntitlement(revocationEpoch = 0L)
        seedActiveBinding()
        assertIs<DecisionTransactionResult.Committed>(
            decisions.transact(scope) {
                DecisionCandidate(
                    nextState = DecisionState(7L, 0L),
                    envelope = envelope(7L)
                )
            }
        )

        val revoked = assertIs<LicenseAdministrationResult.Updated>(
            PostgreSqlLicenseAdministrationStore(dataSource).revokeLicense(
                subject = subject,
                productId = product,
                now = Instant.parse("2026-10-03T12:00:00Z")
            )
        )

        assertEquals(1L, revoked.revocationEpoch)
        assertEquals(DecisionState(7L, 1L), decisions.read(scope))

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT revocation_epoch, revoked_at
                FROM licensing_entitlement
                WHERE subject = ? AND product_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, subject)
                statement.setString(2, product)
                statement.executeQuery().use { result ->
                    require(result.next())
                    assertEquals(1L, result.getLong("revocation_epoch"))
                    assertNotNull(result.getTimestamp("revoked_at"))
                }
            }
            connection.prepareStatement(
                """
                SELECT status, revoked_at
                FROM licensing_device_binding
                WHERE subject = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { result ->
                    require(result.next())
                    assertEquals("REVOKED", result.getString("status"))
                    assertNotNull(result.getTimestamp("revoked_at"))
                }
            }
        }
    }

    @Test
    fun revoke_license_rolls_back_when_entitlement_and_service_state_epochs_diverge() {
        seedEntitlement(revocationEpoch = 0L)
        seedActiveBinding()
        assertIs<DecisionTransactionResult.Committed>(
            decisions.transact(scope) {
                DecisionCandidate(
                    nextState = DecisionState(7L, 3L),
                    envelope = envelope(7L)
                )
            }
        )

        assertIs<LicenseAdministrationResult.Failed>(
            PostgreSqlLicenseAdministrationStore(dataSource).revokeLicense(
                subject = subject,
                productId = product,
                now = Instant.parse("2026-10-03T12:00:00Z")
            )
        )

        assertEquals(DecisionState(7L, 3L), decisions.read(scope))
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT revocation_epoch, revoked_at
                FROM licensing_entitlement
                WHERE subject = ? AND product_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, subject)
                statement.setString(2, product)
                statement.executeQuery().use { result ->
                    require(result.next())
                    assertEquals(0L, result.getLong("revocation_epoch"))
                    assertNull(result.getTimestamp("revoked_at"))
                }
            }
            connection.prepareStatement(
                """
                SELECT status, revoked_at
                FROM licensing_device_binding
                WHERE subject = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, subject)
                statement.executeQuery().use { result ->
                    require(result.next())
                    assertEquals("ACTIVE", result.getString("status"))
                    assertNull(result.getTimestamp("revoked_at"))
                }
            }
        }
    }

    private fun seedEntitlement(revocationEpoch: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO licensing_entitlement (
                    license_id, subject, product_id, features, version,
                    signing_key_id, issued_at, not_before, expires_at,
                    offline_lease_until, revocation_epoch, revoked_at,
                    device_binding_required, device_binding_epoch
                ) VALUES (
                    ?, ?, ?, ARRAY['model.local'], 1,
                    'test-signing-key', ?, ?, NULL,
                    NULL, ?, NULL, TRUE, 2
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "license-revoke-it")
                statement.setString(2, subject)
                statement.setString(3, product)
                val now = java.sql.Timestamp.from(
                    Instant.parse("2026-10-03T11:00:00Z")
                )
                statement.setTimestamp(4, now)
                statement.setTimestamp(5, now)
                statement.setLong(6, revocationEpoch)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun seedActiveBinding() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO licensing_device_binding (
                    binding_id, subject, installation_id,
                    device_key_fingerprint, status, bound_at, revoked_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "binding-revoke-it")
                statement.setString(2, subject)
                statement.setString(3, "installation-revoke-it")
                statement.setString(4, "sha256:device-revoke-it")
                statement.setTimestamp(
                    5,
                    java.sql.Timestamp.from(
                        Instant.parse("2026-10-03T11:00:00Z")
                    )
                )
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun envelope(sequence: Long) = SignedLicenseEnvelope(
        SigningEnvelopeSchemaVersion(1),
        SigningAlgorithm("TEST-ECDSA"),
        SigningKeyReference("test-key"),
        "payload-$sequence".encodeToByteArray(),
        "signature-$sequence".encodeToByteArray()
    )

    private fun resetTables() {
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS licensing_decision_state")
                statement.execute("DROP TABLE IF EXISTS licensing_device_rebind_redemption")
                statement.execute("DROP TABLE IF EXISTS licensing_device_binding")
                statement.execute("DROP TABLE IF EXISTS licensing_activation_redemption")
                statement.execute("DROP TABLE IF EXISTS licensing_entitlement")
            }
        }
    }

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
