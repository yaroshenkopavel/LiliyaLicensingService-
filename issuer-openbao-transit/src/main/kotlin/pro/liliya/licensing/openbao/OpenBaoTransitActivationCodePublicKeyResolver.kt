package pro.liliya.licensing.openbao

import java.security.PublicKey
import java.util.Base64
import pro.liliya.licensing.activation.ActivationCodePublicKeyResolver
import pro.liliya.licensing.activation.ActivationCodeVerifier

class OpenBaoTransitActivationCodePublicKeyResolver private constructor(
    private val keyId: String,
    private val publicKey: PublicKey
) : ActivationCodePublicKeyResolver {
    override fun resolve(keyId: String): PublicKey? =
        publicKey.takeIf { keyId == this.keyId }

    companion object {
        fun create(
            client: OpenBaoTransitClient,
            keyId: String,
            keyName: String,
            keyVersion: Int
        ): OpenBaoTransitActivationCodePublicKeyResolver {
            require(keyId.isNotBlank())
            require(keyName.isNotBlank())
            require(keyVersion > 0)

            val description = when (val result = client.describeKey(keyName)) {
                is OpenBaoTransitDescribeResult.Available -> result.description
                OpenBaoTransitDescribeResult.Unavailable ->
                    error("activation verification key unavailable")
                OpenBaoTransitDescribeResult.Failed ->
                    error("activation verification key inspection failed")
            }
            if (
                description.type != OpenBaoTransitProductionSigningProfile.transitKeyType ||
                !description.supportsSigning ||
                keyVersion !in description.availableVersions
            ) {
                error("activation verification key has unsupported profile")
            }

            val pem = when (
                val result = client.readPublicKeyPem(keyName, keyVersion)
            ) {
                is OpenBaoTransitPublicKeyResult.Available -> result.pem
                OpenBaoTransitPublicKeyResult.Unavailable ->
                    error("activation public key unavailable")
                OpenBaoTransitPublicKeyResult.Failed ->
                    error("activation public key read failed")
            }

            val der = decodePem(pem)
            val publicKey = try {
                ActivationCodeVerifier.ecdsaP256PublicKey(der)
            } finally {
                der.fill(0)
            }
            return OpenBaoTransitActivationCodePublicKeyResolver(keyId, publicKey)
        }

        private fun decodePem(pem: String): ByteArray {
            val body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .filterNot(Char::isWhitespace)
            require(body.isNotBlank()) { "activation public key PEM is empty" }
            return Base64.getDecoder().decode(body)
        }
    }
}
