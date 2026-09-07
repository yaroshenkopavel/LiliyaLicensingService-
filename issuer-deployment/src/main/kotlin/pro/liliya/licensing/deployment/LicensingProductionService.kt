package pro.liliya.licensing.deployment

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.postgresql.ds.PGSimpleDataSource
import pro.liliya.licensing.auth.RequestAuthenticationCredential
import pro.liliya.licensing.auth.RequestAuthenticationFailure
import pro.liliya.licensing.auth.RequestAuthenticationPort
import pro.liliya.licensing.auth.RequestAuthenticationResult
import pro.liliya.licensing.http.AuthenticatedLicenseHttpEndpoint
import pro.liliya.licensing.http.AuthenticatedServiceStateHttpEndpoint
import pro.liliya.licensing.http.LicenseHttpEndpoint
import pro.liliya.licensing.http.LicensingHttpRouter
import pro.liliya.licensing.https.LicenseHttpsHandler
import pro.liliya.licensing.https.ProductionHttpsConfig
import pro.liliya.licensing.https.ProductionHttpsListener
import pro.liliya.licensing.https.TlsPassword
import pro.liliya.licensing.issuer.EntitlementSourcePort
import pro.liliya.licensing.issuer.LicensingIssuerCoordinator
import pro.liliya.licensing.openbao.OpenBaoTransitClient
import pro.liliya.licensing.openbao.OpenBaoTransitDescribeResult
import pro.liliya.licensing.openbao.OpenBaoTransitEndpoint
import pro.liliya.licensing.openbao.OpenBaoTransitHttpClient
import pro.liliya.licensing.openbao.OpenBaoTransitKeyBinding
import pro.liliya.licensing.openbao.OpenBaoTransitLicenseEnvelopeSigner
import pro.liliya.licensing.openbao.OpenBaoTransitProductionSigningProfile
import pro.liliya.licensing.openbao.OpenBaoTransitServiceStateProofSigner
import pro.liliya.licensing.postgres.PostgreSqlDecisionTransactionPort
import pro.liliya.licensing.protocol.LicenseProtocolVersion
import pro.liliya.licensing.protocol.LicenseRequestValidator
import pro.liliya.licensing.runtime.LicensingProductionRuntime
import pro.liliya.licensing.runtime.LicensingRuntimeDependency
import pro.liliya.licensing.runtime.LicensingRuntimeDependencyKind
import pro.liliya.licensing.runtime.LicensingRuntimeDependencyResult
import pro.liliya.licensing.runtime.LicensingRuntimeFailure
import pro.liliya.licensing.runtime.LicensingRuntimeStartResult
import pro.liliya.licensing.runtime.LicensingRuntimeStopResult
import pro.liliya.licensing.signing.LicenseSigningComposition
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.servicestate.ServiceStateEvidenceService
import pro.liliya.licensing.servicestate.ServiceStateSigningKeyId

/**
 * Fixed shared-secret transport authentication for the first production profile.
 *
 * The secret is copied into owned bytes and erased on close. Authentication remains transport
 * request evidence only and never becomes entitlement, LicensePolicy, Authority or Execution.
 */
class SharedSecretRequestAuthentication(
    secret: ByteArray
) : RequestAuthenticationPort, AutoCloseable {
    private val lock = Any()
    private val expected = secret.copyOf()
    private var closed = false

    init {
        require(expected.isNotEmpty()) { "request-authentication secret must not be empty" }
    }

    override fun authenticate(
        credential: RequestAuthenticationCredential?
    ): RequestAuthenticationResult = synchronized(lock) {
        if (closed) {
            return@synchronized RequestAuthenticationResult.Rejected(
                RequestAuthenticationFailure.UNAVAILABLE
            )
        }
        if (credential == null) {
            return@synchronized RequestAuthenticationResult.Rejected(
                RequestAuthenticationFailure.MISSING
            )
        }

        val candidate = credential.copyBytes()
        try {
            if (MessageDigest.isEqual(expected, candidate)) {
                RequestAuthenticationResult.Authenticated
            } else {
                RequestAuthenticationResult.Rejected(
                    RequestAuthenticationFailure.INVALID
                )
            }
        } finally {
            candidate.fill(0)
        }
    }

    fun isReady(): Boolean = synchronized(lock) { !closed }

    override fun close() = synchronized(lock) {
        if (!closed) {
            expected.fill(0)
            closed = true
        }
    }

    override fun toString(): String =
        "SharedSecretRequestAuthentication(secret=<redacted>,closed=" + closed + ")"
}

class PostgreSqlRuntimeReadinessDependency(
    private val dataSource: PGSimpleDataSource
) : LicensingRuntimeDependency {
    override val kind: LicensingRuntimeDependencyKind =
        LicensingRuntimeDependencyKind.POSTGRESQL

    override fun prepare(): LicensingRuntimeDependencyResult =
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT replay_sequence, revocation_epoch, envelope_schema_version,
                           algorithm, signing_key_reference, canonical_payload, signature
                    FROM licensing_decision_state
                    WHERE 1 = 0
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        result.metaData.columnCount
                    }
                }
            }
            LicensingRuntimeDependencyResult.Ready
        } catch (_: Exception) {
            LicensingRuntimeDependencyResult.Failed(
                LicensingRuntimeFailure.POSTGRESQL_UNAVAILABLE
            )
        }

    override fun close() = Unit

    override fun toString(): String =
        "PostgreSqlRuntimeReadinessDependency(dataSource=<redacted>)"
}

data class OpenBaoRuntimeKeyRequirement(
    val keyName: String,
    val keyVersion: Int
) {
    init {
        require(keyName.isNotBlank()) { "OpenBao readiness key name must not be blank" }
        require(keyVersion > 0) { "OpenBao readiness key version must be positive" }
    }

    override fun toString(): String =
        "OpenBaoRuntimeKeyRequirement(keyName=<redacted>,keyVersion=" + keyVersion + ")"
}

class OpenBaoRuntimeReadinessDependency(
    private val client: OpenBaoTransitClient,
    requiredKeys: Collection<OpenBaoRuntimeKeyRequirement>
) : LicensingRuntimeDependency {
    private val requiredKeys = requiredKeys.toList()

    constructor(
        client: OpenBaoTransitClient,
        keyName: String,
        keyVersion: Int
    ) : this(
        client = client,
        requiredKeys = listOf(OpenBaoRuntimeKeyRequirement(keyName, keyVersion))
    )

    init {
        require(this.requiredKeys.isNotEmpty()) {
            "OpenBao readiness must require at least one exact key"
        }
        require(
            this.requiredKeys.map { it.keyName to it.keyVersion }.toSet().size ==
                this.requiredKeys.size
        ) {
            "OpenBao readiness exact key requirements must be unique"
        }
    }

    override val kind: LicensingRuntimeDependencyKind =
        LicensingRuntimeDependencyKind.OPENBAO_TRANSIT

    override fun prepare(): LicensingRuntimeDependencyResult {
        for (required in requiredKeys) {
            val description = when (val result = client.describeKey(required.keyName)) {
                is OpenBaoTransitDescribeResult.Available -> result.description
                OpenBaoTransitDescribeResult.Unavailable,
                OpenBaoTransitDescribeResult.Failed -> return failed()
            }

            if (
                description.type !=
                    OpenBaoTransitProductionSigningProfile.transitKeyType ||
                !description.supportsSigning ||
                required.keyVersion !in description.availableVersions
            ) {
                return failed()
            }
        }
        return LicensingRuntimeDependencyResult.Ready
    }

    override fun close() = Unit

    override fun toString(): String =
        "OpenBaoRuntimeReadinessDependency(client=<redacted>,requiredKeys=" +
            requiredKeys.size + ")"

    private fun failed(): LicensingRuntimeDependencyResult.Failed =
        LicensingRuntimeDependencyResult.Failed(
            LicensingRuntimeFailure.OPENBAO_UNAVAILABLE
        )
}

class RequestAuthenticationRuntimeReadinessDependency(
    private val authentication: SharedSecretRequestAuthentication
) : LicensingRuntimeDependency {
    override val kind: LicensingRuntimeDependencyKind =
        LicensingRuntimeDependencyKind.REQUEST_AUTHENTICATION

    override fun prepare(): LicensingRuntimeDependencyResult =
        if (authentication.isReady()) {
            LicensingRuntimeDependencyResult.Ready
        } else {
            LicensingRuntimeDependencyResult.Failed(
                LicensingRuntimeFailure.REQUEST_AUTHENTICATION_UNAVAILABLE
            )
        }

    override fun close() = Unit

    override fun toString(): String =
        "RequestAuthenticationRuntimeReadinessDependency(authentication=<redacted>)"
}

/**
 * Real production service composition.
 *
 * The entitlement source is supplied by exactly one external provider. This composition owns
 * transport/runtime assembly only and does not invent billing, enrollment, LicensePolicy,
 * Capability Authority or Execution semantics.
 */
class LicensingProductionService private constructor(
    private val runtime: LicensingProductionRuntime,
    private val httpsConfig: ProductionHttpsConfig,
    private val authentication: SharedSecretRequestAuthentication,
    private val runtimeMaterial: ProductionRuntimeMaterial,
    private val deploymentConfig: LicensingDeploymentConfig
) : AutoCloseable {
    fun start(): LicensingRuntimeStartResult = runtime.start()

    fun isReady(): Boolean = runtime.isReady()

    fun stop(): LicensingRuntimeStopResult = runtime.stop()

    override fun close() {
        runCatching {
            if (runtime.isReady()) {
                runtime.stop()
            } else {
                runtime.close()
            }
        }
        runCatching { httpsConfig.close() }
        runCatching { authentication.close() }
        runCatching { runtimeMaterial.close() }
        runCatching { deploymentConfig.close() }
    }

    override fun toString(): String =
        "LicensingProductionService(runtime=" + runtime.state() +
            ",https=<redacted>,authentication=<redacted>,config=<redacted>)"

    companion object {
        fun create(
            deploymentConfig: LicensingDeploymentConfig,
            runtimeMaterial: ProductionRuntimeMaterial,
            entitlementSource: EntitlementSourcePort
        ): LicensingProductionService {
            val dataSource = PGSimpleDataSource().apply {
                setURL(deploymentConfig.postgresJdbcUrl)
                user = deploymentConfig.postgresUsername
                deploymentConfig.postgresPassword.useChars { chars ->
                    password = chars.concatToString()
                }
            }

            val openBaoClient = OpenBaoTransitHttpClient(
                endpoint = OpenBaoTransitEndpoint(deploymentConfig.openBaoAddress),
                tokenProvider = {
                    var token = ""
                    deploymentConfig.openBaoToken.useChars { chars ->
                        token = chars.concatToString()
                    }
                    token
                }
            )

            val signer = OpenBaoTransitLicenseEnvelopeSigner(
                bindings = listOf(
                    OpenBaoTransitKeyBinding(
                        keyReference = SigningKeyReference(
                            deploymentConfig.openBaoKeyReference
                        ),
                        keyName = runtimeMaterial.openBaoKeyName,
                        keyVersion = runtimeMaterial.openBaoKeyVersion
                    )
                ),
                client = openBaoClient
            )

            val transactions = PostgreSqlDecisionTransactionPort(dataSource)
            val authentication = createAuthentication(deploymentConfig)

            val serviceStateSigner = OpenBaoTransitServiceStateProofSigner(
                client = openBaoClient,
                keyName = runtimeMaterial.serviceStateOpenBaoKeyName,
                keyVersion = runtimeMaterial.serviceStateOpenBaoKeyVersion
            )
            val serviceStateService = ServiceStateEvidenceService(
                states = transactions,
                signer = serviceStateSigner,
                signingKeyId = ServiceStateSigningKeyId(
                    runtimeMaterial.serviceStateOpenBaoKeyReference
                )
            )

            val endpoint = AuthenticatedLicenseHttpEndpoint(
                delegate = LicenseHttpEndpoint(
                    LicensingIssuerCoordinator(
                        validator = LicenseRequestValidator(
                            supportedVersion = LicenseProtocolVersion(1)
                        ),
                        source = entitlementSource,
                        transactions = transactions,
                        signing = LicenseSigningComposition(signer)
                    )
                ),
                authentication = authentication
            )
            val serviceStateEndpoint = AuthenticatedServiceStateHttpEndpoint(
                service = serviceStateService,
                authentication = authentication
            )
            val router = LicensingHttpRouter(
                entitlement = endpoint,
                serviceState = serviceStateEndpoint
            )

            val tlsPassword = run {
                var result: TlsPassword? = null
                runtimeMaterial.tlsKeyStorePassword.useChars { chars ->
                    result = TlsPassword.of(chars)
                }
                requireNotNull(result)
            }
            val httpsConfig = ProductionHttpsConfig(
                host = deploymentConfig.listenerHost,
                port = deploymentConfig.listenerPort,
                keyStorePath = runtimeMaterial.tlsKeyStorePath,
                keyStorePassword = tlsPassword
            )

            val runtimeRef = AtomicReference<LicensingProductionRuntime?>()
            val listener = ProductionHttpsListener(
                config = httpsConfig,
                handler = LicenseHttpsHandler(router::handle),
                readiness = { runtimeRef.get()?.isReady() == true }
            )

            val runtime = LicensingProductionRuntime(
                dependencies = listOf(
                    PostgreSqlRuntimeReadinessDependency(dataSource),
                    OpenBaoRuntimeReadinessDependency(
                        client = openBaoClient,
                        requiredKeys = listOf(
                            OpenBaoRuntimeKeyRequirement(
                                keyName = runtimeMaterial.openBaoKeyName,
                                keyVersion = runtimeMaterial.openBaoKeyVersion
                            ),
                            OpenBaoRuntimeKeyRequirement(
                                keyName = runtimeMaterial.serviceStateOpenBaoKeyName,
                                keyVersion = runtimeMaterial.serviceStateOpenBaoKeyVersion
                            )
                        )
                    ),
                    RequestAuthenticationRuntimeReadinessDependency(authentication)
                ),
                listener = listener
            )
            runtimeRef.set(runtime)

            return LicensingProductionService(
                runtime = runtime,
                httpsConfig = httpsConfig,
                authentication = authentication,
                runtimeMaterial = runtimeMaterial,
                deploymentConfig = deploymentConfig
            )
        }

        private fun createAuthentication(
            config: LicensingDeploymentConfig
        ): SharedSecretRequestAuthentication {
            var secret: ByteArray? = null
            config.requestAuthenticationSecret.useChars { chars ->
                secret = chars.concatToString().encodeToByteArray()
            }
            val owned = requireNotNull(secret)
            return try {
                SharedSecretRequestAuthentication(owned)
            } finally {
                owned.fill(0)
            }
        }
    }
}
