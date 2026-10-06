package pro.liliya.licensing.activation

import java.time.Instant

data class DeviceRebindRequest(
    val rebindCode: String,
    val attemptId: String,
    val installationId: String,
    val deviceKeyFingerprint: String
) {
    init {
        require(rebindCode.isNotBlank())
        require(attemptId.isNotBlank())
        require(installationId.isNotBlank())
        require(deviceKeyFingerprint.isNotBlank())
    }

    override fun toString(): String =
        "DeviceRebindRequest(rebindCode=<redacted>,attemptId=<redacted>," +
            "installationId=<redacted>,deviceKeyFingerprint=<redacted>)"
}

data class DeviceRebindRecord(
    val codeId: String,
    val attemptId: String,
    val subject: String,
    val productId: String,
    val deviceBindingEpoch: Long,
    val installationId: String,
    val deviceKeyFingerprint: String,
    val reboundAt: Instant
)

sealed interface DeviceRebindStoreResult {
    data class Created(val record: DeviceRebindRecord) : DeviceRebindStoreResult
    data class Replay(val record: DeviceRebindRecord) : DeviceRebindStoreResult
    data object Exhausted : DeviceRebindStoreResult
    data object ActiveDeviceExists : DeviceRebindStoreResult
    data object StaleBindingEpoch : DeviceRebindStoreResult
    data object EntitlementUnavailable : DeviceRebindStoreResult
    data object Failed : DeviceRebindStoreResult
}

fun interface DeviceRebindStore {
    fun rebind(
        claims: DeviceRebindCodeClaims,
        attemptId: String,
        installationId: String,
        deviceKeyFingerprint: String,
        now: Instant
    ): DeviceRebindStoreResult
}

sealed interface DeviceRebindResult {
    data class Rebound(
        val subject: String,
        val productId: String
    ) : DeviceRebindResult

    data class IdempotentReplay(
        val subject: String,
        val productId: String
    ) : DeviceRebindResult

    data object InvalidCode : DeviceRebindResult
    data object ExpiredCode : DeviceRebindResult
    data object CodeExhausted : DeviceRebindResult
    data object DeviceLimitReached : DeviceRebindResult
    data object ReplacementStateChanged : DeviceRebindResult
    data object EntitlementUnavailable : DeviceRebindResult
    data object StoreUnavailable : DeviceRebindResult
}

class DeviceRebindService(
    private val publicKeys: ActivationCodePublicKeyResolver,
    private val store: DeviceRebindStore
) {
    fun rebind(
        request: DeviceRebindRequest,
        now: Instant
    ): DeviceRebindResult {
        val claims = when (
            val verified = DeviceRebindCodeVerifier.verify(
                encoded = request.rebindCode,
                keys = publicKeys,
                now = now
            )
        ) {
            is DeviceRebindCodeVerificationResult.Verified -> verified.claims
            DeviceRebindCodeVerificationResult.Expired ->
                return DeviceRebindResult.ExpiredCode
            DeviceRebindCodeVerificationResult.Invalid ->
                return DeviceRebindResult.InvalidCode
        }

        return when (
            val result = store.rebind(
                claims = claims,
                attemptId = request.attemptId,
                installationId = request.installationId,
                deviceKeyFingerprint = request.deviceKeyFingerprint,
                now = now
            )
        ) {
            is DeviceRebindStoreResult.Created ->
                DeviceRebindResult.Rebound(
                    subject = result.record.subject,
                    productId = result.record.productId
                )
            is DeviceRebindStoreResult.Replay ->
                DeviceRebindResult.IdempotentReplay(
                    subject = result.record.subject,
                    productId = result.record.productId
                )
            DeviceRebindStoreResult.Exhausted ->
                DeviceRebindResult.CodeExhausted
            DeviceRebindStoreResult.ActiveDeviceExists ->
                DeviceRebindResult.DeviceLimitReached
            DeviceRebindStoreResult.StaleBindingEpoch ->
                DeviceRebindResult.ReplacementStateChanged
            DeviceRebindStoreResult.EntitlementUnavailable ->
                DeviceRebindResult.EntitlementUnavailable
            DeviceRebindStoreResult.Failed ->
                DeviceRebindResult.StoreUnavailable
        }
    }
}
