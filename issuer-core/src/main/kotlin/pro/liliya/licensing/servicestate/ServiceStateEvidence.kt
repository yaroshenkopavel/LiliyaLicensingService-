package pro.liliya.licensing.servicestate

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import pro.liliya.licensing.issuer.DecisionScope
import pro.liliya.licensing.issuer.DecisionState

@JvmInline
value class ServiceStateProtocolVersion(val value: Long) {
    init {
        require(value > 0L) { "service-state protocol version must be positive" }
    }
}

@JvmInline
value class ServiceStateEvidenceProfile(val value: String) {
    init {
        require(value.isNotBlank()) { "service-state evidence profile must not be blank" }
    }
}

@JvmInline
value class ServiceStateSigningKeyId(val value: String) {
    init {
        require(value.isNotBlank()) { "service-state signing key id must not be blank" }
    }
}

enum class ServiceStateEvidencePurpose {
    SECURITY_STATE
}

data class ServiceStateScope(
    val productId: String,
    val subject: String
) {
    init {
        require(productId.isNotBlank()) { "service-state productId must not be blank" }
        require(subject.isNotBlank()) { "service-state subject must not be blank" }
    }

    fun decisionScope(): DecisionScope =
        DecisionScope(subject = subject, productId = productId)

    override fun toString(): String =
        "ServiceStateScope(productId=" + productId + ",subject=<redacted>)"
}

data class ServiceStateSecurityState(
    val scope: ServiceStateScope,
    val revocationEpoch: Long,
    val replaySequence: Long
) {
    init {
        require(revocationEpoch >= 0L) { "service-state revocation epoch must be non-negative" }
        require(replaySequence >= 0L) { "service-state replay sequence must be non-negative" }
    }

    override fun toString(): String =
        "ServiceStateSecurityState(scope=" + scope +
            ",revocationEpoch=" + revocationEpoch +
            ",replaySequence=" + replaySequence + ")"
}

class ServiceStateOpaquePayload private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String =
        "ServiceStateOpaquePayload(size=" + bytes.size + ",content=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): ServiceStateOpaquePayload {
            require(bytes.isNotEmpty()) { "service-state payload must not be empty" }
            return ServiceStateOpaquePayload(bytes.copyOf())
        }
    }
}

class ServiceStateAuthenticationProof private constructor(
    private val bytes: ByteArray
) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun toString(): String =
        "ServiceStateAuthenticationProof(size=" + bytes.size + ",value=<redacted>)"

    companion object {
        fun of(bytes: ByteArray): ServiceStateAuthenticationProof {
            require(bytes.isNotEmpty()) { "service-state proof must not be empty" }
            return ServiceStateAuthenticationProof(bytes.copyOf())
        }
    }
}

class ServiceStateEnvelope(
    val protocolVersion: ServiceStateProtocolVersion,
    val purpose: ServiceStateEvidencePurpose,
    val profile: ServiceStateEvidenceProfile,
    val signingKeyId: ServiceStateSigningKeyId,
    val payload: ServiceStateOpaquePayload,
    val proof: ServiceStateAuthenticationProof
) {
    override fun toString(): String =
        "ServiceStateEnvelope(protocolVersion=" + protocolVersion.value +
            ",purpose=" + purpose +
            ",profile=" + profile.value +
            ",signingKeyId=" + signingKeyId.value +
            ",payload=<redacted>,proof=<redacted>)"
}

fun interface CurrentDecisionStateReadPort {
    fun read(scope: DecisionScope): DecisionState?
}

/**
 * Exact backend-side encoder for frozen Core LicenseServiceSecurityStateCanonicalCodec v1.
 *
 * v0.1 intentionally carries replay + revocation only. Server time remains absent so this
 * slice does not create a new clock-authority claim.
 */
object ServiceStateCanonicalCodec {
    private const val MAGIC = 0x4C535331

    fun encode(state: ServiceStateSecurityState): ServiceStateOpaquePayload =
        ServiceStateOpaquePayload.of(
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(MAGIC)
                    data.writeString(state.scope.productId)
                    data.writeString(state.scope.subject)
                    data.writeBoolean(true)
                    data.writeLong(state.revocationEpoch)
                    data.writeBoolean(true)
                    data.writeLong(state.replaySequence)
                    data.writeBoolean(false)
                }
                output.toByteArray()
            }
        )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}

/**
 * Exact backend-side encoder for frozen Core LicenseServiceAuthenticationTranscript v1.
 */
object ServiceStateAuthenticationTranscriptCodec {
    private const val MAGIC = 0x4C535354

    fun encode(
        protocolVersion: ServiceStateProtocolVersion,
        purpose: ServiceStateEvidencePurpose,
        profile: ServiceStateEvidenceProfile,
        signingKeyId: ServiceStateSigningKeyId,
        payload: ServiceStateOpaquePayload
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeLong(protocolVersion.value)
                data.writeString(purpose.name)
                data.writeString(profile.value)
                data.writeString(signingKeyId.value)
                val payloadBytes = payload.copyBytes()
                data.writeInt(payloadBytes.size)
                data.write(payloadBytes)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}

sealed interface ServiceStateSigningResult {
    data class Signed(val proof: ServiceStateAuthenticationProof) : ServiceStateSigningResult
    data object KeyUnavailable : ServiceStateSigningResult
    data object Rejected : ServiceStateSigningResult
    data object Failed : ServiceStateSigningResult
}

fun interface ServiceStateProofSigner {
    fun sign(transcript: ByteArray): ServiceStateSigningResult
}

sealed interface ServiceStateEvidenceResult {
    data class Issued(val envelope: ServiceStateEnvelope) : ServiceStateEvidenceResult
    data object StateUnavailable : ServiceStateEvidenceResult
    data object SigningKeyUnavailable : ServiceStateEvidenceResult
    data object SigningRejected : ServiceStateEvidenceResult
    data object InternalFailure : ServiceStateEvidenceResult
}

class ServiceStateEvidenceService(
    private val states: CurrentDecisionStateReadPort,
    private val signer: ServiceStateProofSigner,
    private val protocolVersion: ServiceStateProtocolVersion = ServiceStateProtocolVersion(1),
    private val profile: ServiceStateEvidenceProfile =
        ServiceStateEvidenceProfile("ECDSA-P256-SHA256-SERVICE-STATE-V1"),
    private val signingKeyId: ServiceStateSigningKeyId
) {
    fun issue(scope: ServiceStateScope): ServiceStateEvidenceResult {
        val current = try {
            states.read(scope.decisionScope())
        } catch (_: RuntimeException) {
            null
        } ?: return ServiceStateEvidenceResult.StateUnavailable

        val payload = ServiceStateCanonicalCodec.encode(
            ServiceStateSecurityState(
                scope = scope,
                revocationEpoch = current.revocationEpoch,
                replaySequence = current.replaySequence
            )
        )
        val transcript = ServiceStateAuthenticationTranscriptCodec.encode(
            protocolVersion = protocolVersion,
            purpose = ServiceStateEvidencePurpose.SECURITY_STATE,
            profile = profile,
            signingKeyId = signingKeyId,
            payload = payload
        )

        return when (val result = signer.sign(transcript)) {
            is ServiceStateSigningResult.Signed ->
                ServiceStateEvidenceResult.Issued(
                    ServiceStateEnvelope(
                        protocolVersion = protocolVersion,
                        purpose = ServiceStateEvidencePurpose.SECURITY_STATE,
                        profile = profile,
                        signingKeyId = signingKeyId,
                        payload = payload,
                        proof = result.proof
                    )
                )
            ServiceStateSigningResult.KeyUnavailable ->
                ServiceStateEvidenceResult.SigningKeyUnavailable
            ServiceStateSigningResult.Rejected ->
                ServiceStateEvidenceResult.SigningRejected
            ServiceStateSigningResult.Failed ->
                ServiceStateEvidenceResult.InternalFailure
        }
    }

    override fun toString(): String =
        "ServiceStateEvidenceService(states=<redacted>,signer=<redacted>," +
            "protocolVersion=" + protocolVersion.value +
            ",profile=" + profile.value +
            ",signingKeyId=" + signingKeyId.value + ")"
}
