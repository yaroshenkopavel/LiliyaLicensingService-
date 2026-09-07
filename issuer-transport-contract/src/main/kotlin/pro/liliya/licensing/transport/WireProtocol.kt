package pro.liliya.licensing.transport

import pro.liliya.licensing.protocol.LicenseServiceFailure
import pro.liliya.licensing.protocol.LicenseServiceRequest
import pro.liliya.licensing.signing.SignedLicenseEnvelope

@JvmInline
value class LicenseWireProtocolVersion(val value: Int) {
    init { require(value > 0) { "wire protocol version must be positive" } }
}

enum class LicenseTransportFailure {
    INVALID_LOCAL_REQUEST,
    CONNECT_FAILURE,
    TLS_FAILURE,
    TIMEOUT,
    CANCELLED,
    PROTOCOL_FAILURE,
    SERVICE_UNAVAILABLE
}

sealed interface LicenseWireRequest {
    val wireVersion: LicenseWireProtocolVersion

    data class ServiceRequest(
        override val wireVersion: LicenseWireProtocolVersion,
        val request: LicenseServiceRequest
    ) : LicenseWireRequest
}

sealed interface LicenseWireResponse {
    val wireVersion: LicenseWireProtocolVersion

    data class SignedSuccess(
        override val wireVersion: LicenseWireProtocolVersion,
        val envelope: SignedLicenseEnvelope
    ) : LicenseWireResponse

    data class ServiceRejected(
        override val wireVersion: LicenseWireProtocolVersion,
        val reason: LicenseServiceFailure
    ) : LicenseWireResponse
}

sealed interface LicenseWireDecodeResult<out T> {
    data class Decoded<T>(val value: T) : LicenseWireDecodeResult<T>
    data class Rejected(val reason: LicenseTransportFailure) : LicenseWireDecodeResult<Nothing>
}
