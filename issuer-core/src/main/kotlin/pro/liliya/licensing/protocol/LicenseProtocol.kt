package pro.liliya.licensing.protocol

import java.time.Instant

@JvmInline
value class LicenseProtocolVersion(val value: Int) {
    init { require(value > 0) { "protocol version must be positive" } }
}

enum class LicenseOperation {
    ISSUE,
    REFRESH
}

enum class LicenseServiceFailure {
    INVALID_REQUEST,
    UNSUPPORTED_PROTOCOL,
    AUTHENTICATION_REQUIRED,
    SUBJECT_NOT_ELIGIBLE,
    PRODUCT_NOT_ELIGIBLE,
    ENROLLMENT_REQUIRED,
    ENROLLMENT_REJECTED,
    DEVICE_PROOF_REJECTED,
    REFRESH_REJECTED,
    REPLAY_CONFLICT,
    REVOCATION_CONFLICT,
    IDEMPOTENCY_CONFLICT,
    SIGNING_KEY_UNAVAILABLE,
    ENTITLEMENT_SOURCE_UNAVAILABLE,
    INTERNAL_FAILURE
}

data class LicenseServiceRequest(
    val protocolVersion: LicenseProtocolVersion,
    val operation: LicenseOperation,
    val productId: String,
    val subjectReference: String,
    val requestId: String? = null,
    val enrollmentReference: String? = null
) {
    init {
        require(productId.isNotBlank()) { "productId must not be blank" }
        require(subjectReference.isNotBlank()) { "subjectReference must not be blank" }
        require(requestId == null || requestId.isNotBlank()) { "requestId must not be blank" }
        require(enrollmentReference == null || enrollmentReference.isNotBlank()) {
            "enrollmentReference must not be blank"
        }
    }

    override fun toString(): String =
        "LicenseServiceRequest(protocolVersion=" + protocolVersion +
            ",operation=" + operation +
            ",productId=" + productId +
            ",subjectReference=<redacted>,requestId=<redacted>," +
            "enrollmentReference=<redacted>)"
}

sealed interface LicenseServiceRequestCreationResult {
    data class Created(val request: LicenseServiceRequest) : LicenseServiceRequestCreationResult
    data class Rejected(val reason: LicenseServiceFailure) : LicenseServiceRequestCreationResult
}

object LicenseServiceRequestFactory {
    fun create(
        protocolVersion: Int,
        operation: String,
        productId: String,
        subjectReference: String,
        requestId: String? = null,
        enrollmentReference: String? = null
    ): LicenseServiceRequestCreationResult {
        if (protocolVersion <= 0) {
            return LicenseServiceRequestCreationResult.Rejected(
                LicenseServiceFailure.INVALID_REQUEST
            )
        }

        val parsedOperation = try {
            LicenseOperation.valueOf(operation)
        } catch (_: IllegalArgumentException) {
            return LicenseServiceRequestCreationResult.Rejected(
                LicenseServiceFailure.INVALID_REQUEST
            )
        }

        if (
            productId.isBlank() ||
            subjectReference.isBlank() ||
            (requestId != null && requestId.isBlank()) ||
            (enrollmentReference != null && enrollmentReference.isBlank())
        ) {
            return LicenseServiceRequestCreationResult.Rejected(
                LicenseServiceFailure.INVALID_REQUEST
            )
        }

        return LicenseServiceRequestCreationResult.Created(
            LicenseServiceRequest(
                protocolVersion = LicenseProtocolVersion(protocolVersion),
                operation = parsedOperation,
                productId = productId,
                subjectReference = subjectReference,
                requestId = requestId,
                enrollmentReference = enrollmentReference
            )
        )
    }
}

sealed interface LicenseRequestValidationResult {
    data class Accepted(val request: LicenseServiceRequest) : LicenseRequestValidationResult
    data class Rejected(val reason: LicenseServiceFailure) : LicenseRequestValidationResult
}

class LicenseRequestValidator(
    private val supportedVersion: LicenseProtocolVersion
) {
    fun validate(request: LicenseServiceRequest): LicenseRequestValidationResult =
        if (request.protocolVersion != supportedVersion) {
            LicenseRequestValidationResult.Rejected(LicenseServiceFailure.UNSUPPORTED_PROTOCOL)
        } else {
            LicenseRequestValidationResult.Accepted(request)
        }
}

data class EntitlementDecision(
    val licenseId: String,
    val subject: String,
    val productId: String,
    val features: Set<String>,
    val version: Long,
    val signingKeyId: String,
    val issuedAt: Instant,
    val notBefore: Instant,
    val expiresAt: Instant?,
    val offlineLeaseUntil: Instant?,
    val revocationEpoch: Long,
    val replaySequence: Long?
) {
    override fun toString(): String =
        "EntitlementDecision(licenseId=" + licenseId +
            ",subject=<redacted>,productId=" + productId +
            ",featuresCount=" + features.size +
            ",version=" + version +
            ",signingKeyId=" + signingKeyId +
            ",issuedAt=" + issuedAt +
            ",notBefore=" + notBefore +
            ",expiresAt=" + expiresAt +
            ",offlineLeaseUntil=" + offlineLeaseUntil +
            ",revocationEpoch=" + revocationEpoch +
            ",replaySequence=" + replaySequence + ")"
}
