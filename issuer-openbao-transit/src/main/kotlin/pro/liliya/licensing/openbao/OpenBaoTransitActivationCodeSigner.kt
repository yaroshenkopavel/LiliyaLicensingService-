package pro.liliya.licensing.openbao

import pro.liliya.licensing.activation.ActivationCodeSigner

/**
 * Exact-version OpenBao Transit signer for owner-issued Activation Codes.
 *
 * Activation signing is separate from License signing. No key discovery,
 * latest-version fallback, or private-key export is allowed.
 */
class OpenBaoTransitActivationCodeSigner(
    private val client: OpenBaoTransitClient,
    private val keyName: String,
    private val keyVersion: Int
) : ActivationCodeSigner {
    init {
        require(keyName.isNotBlank())
        require(keyVersion > 0)
    }

    override fun sign(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())
        val description = when (val result = client.describeKey(keyName)) {
            is OpenBaoTransitDescribeResult.Available -> result.description
            OpenBaoTransitDescribeResult.Unavailable ->
                error("activation signing key unavailable")
            OpenBaoTransitDescribeResult.Failed ->
                error("activation signing key inspection failed")
        }

        if (
            description.type != OpenBaoTransitProductionSigningProfile.transitKeyType ||
            !description.supportsSigning
        ) {
            error("activation signing key has unsupported profile")
        }
        if (keyVersion !in description.availableVersions) {
            error("activation signing key version unavailable")
        }

        val signature = when (
            val result = client.sign(
                keyName = keyName,
                keyVersion = keyVersion,
                input = payload
            )
        ) {
            is OpenBaoTransitSignResult.Signed -> result.signature
            OpenBaoTransitSignResult.Unavailable ->
                error("activation signing key unavailable")
            OpenBaoTransitSignResult.Rejected ->
                error("activation signing rejected")
            OpenBaoTransitSignResult.Failed ->
                error("activation signing failed")
        }

        if (signature.version != keyVersion) {
            error("activation signing returned unexpected key version")
        }
        return signature.bytes.copyOf()
    }

    override fun toString(): String =
        "OpenBaoTransitActivationCodeSigner(client=<redacted>,keyName=<redacted>," +
            "keyVersion=$keyVersion)"
}
