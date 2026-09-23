package pro.liliya.licensing.activation

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.postgresql.ds.PGSimpleDataSource

class PostgreSqlActivationStoreIntegrationTest {
    private val dataSource = PGSimpleDataSource().apply {
        setURL(requiredEnv("TEST_POSTGRES_URL"))
        user = requiredEnv("TEST_POSTGRES_USER")
        password = requiredEnv("TEST_POSTGRES_PASSWORD")
    }

    private lateinit var store: PostgreSqlActivationStore

    @BeforeTest
    fun setUp() {
        dataSource.connection.use { connection ->
            PostgreSqlActivationSchema.initialize(connection)
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE licensing_client_credential")
                statement.execute("TRUNCATE TABLE licensing_activation_code")
            }
        }
        store = PostgreSqlActivationStore(dataSource)
    }

    @AfterTest
    fun tearDown() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE TABLE licensing_client_credential")
                statement.execute("TRUNCATE TABLE licensing_activation_code")
            }
        }
    }

    @Test
    fun activation_code_is_consumed_exactly_once_and_credential_is_bound_to_grant() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        val codeDigest = ActivationDigest.sha256("single-use-code".encodeToByteArray())
        val credentialDigest = ActivationDigest.sha256("client-credential".encodeToByteArray())

        insertActivation(
            codeDigest = codeDigest,
            subject = "subject-1",
            productId = "liliya-pro",
            createdAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(600)
        )

        val first = assertIs<ActivationStoreConsumeResult.Consumed>(
            store.consume(codeDigest, credentialDigest, now)
        )
        assertEquals("subject-1", first.grant.subject)
        assertEquals("liliya-pro", first.grant.productId)

        val identity = assertIs<ActivationCredentialLookupResult.Active>(
            store.lookupCredential(credentialDigest)
        )
        assertEquals("subject-1", identity.identity.subject)
        assertEquals("liliya-pro", identity.identity.productId)

        assertIs<ActivationStoreConsumeResult.Rejected>(
            store.consume(
                codeDigest,
                ActivationDigest.sha256("second-credential".encodeToByteArray()),
                now.plusSeconds(1)
            )
        )

        assertTrue(store.revokeCredential(credentialDigest, now.plusSeconds(2)))
        assertIs<ActivationCredentialLookupResult.Rejected>(
            store.lookupCredential(credentialDigest)
        )
    }

    @Test
    fun expired_activation_code_fails_closed_without_creating_credential() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        val codeDigest = ActivationDigest.sha256("expired-code".encodeToByteArray())
        val credentialDigest = ActivationDigest.sha256("must-not-be-issued".encodeToByteArray())

        insertActivation(
            codeDigest = codeDigest,
            subject = "subject-2",
            productId = "liliya-pro",
            createdAt = now.minusSeconds(600),
            expiresAt = now.minusSeconds(1)
        )

        assertIs<ActivationStoreConsumeResult.Rejected>(
            store.consume(codeDigest, credentialDigest, now)
        )
        assertIs<ActivationCredentialLookupResult.Rejected>(
            store.lookupCredential(credentialDigest)
        )
    }

    private fun insertActivation(
        codeDigest: ByteArray,
        subject: String,
        productId: String,
        createdAt: Instant,
        expiresAt: Instant
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO licensing_activation_code(
                    code_hash,
                    subject,
                    product_id,
                    created_at,
                    expires_at,
                    consumed_at
                )
                VALUES (?, ?, ?, ?, ?, NULL)
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, codeDigest)
                statement.setString(2, subject)
                statement.setString(3, productId)
                statement.setTimestamp(4, java.sql.Timestamp.from(createdAt))
                statement.setTimestamp(5, java.sql.Timestamp.from(expiresAt))
                statement.executeUpdate()
            }
        }
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("missing integration environment: $name")
}
