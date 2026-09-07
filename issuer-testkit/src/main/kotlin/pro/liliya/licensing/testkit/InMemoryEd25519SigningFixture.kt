package pro.liliya.licensing.testkit

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import pro.liliya.licensing.signing.LicenseEnvelopeSigner
import pro.liliya.licensing.signing.SignedLicenseEnvelope
import pro.liliya.licensing.signing.SigningFailure
import pro.liliya.licensing.signing.SigningKeyReference
import pro.liliya.licensing.signing.SigningResult

class InMemoryEd25519SigningFixture private constructor(
    private val keys: Map<SigningKeyReference, KeyPair>,
    private val retired: Set<SigningKeyReference>
) : LicenseEnvelopeSigner {

    override fun sign(
        canonicalPayload: ByteArray,
        keyReference: SigningKeyReference
    ): SigningResult {
        if (keyReference in retired) {
            return SigningResult.Rejected(SigningFailure.KEY_RETIRED)
        }
        val keyPair = keys[keyReference]
            ?: return SigningResult.Rejected(SigningFailure.KEY_UNAVAILABLE)

        return try {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update(canonicalPayload)
            SigningResult.Signed(
                SignedLicenseEnvelope(
                    keyReference = keyReference,
                    canonicalPayload = canonicalPayload,
                    signature = signer.sign()
                )
            )
        } catch (_: RuntimeException) {
            SigningResult.Rejected(SigningFailure.INTERNAL_FAILURE)
        }
    }

    fun verify(envelope: SignedLicenseEnvelope): Boolean {
        val keyPair = keys[envelope.keyReference] ?: return false
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(keyPair.public)
            verifier.update(envelope.copyCanonicalPayload())
            verifier.verify(envelope.copySignature())
        } catch (_: RuntimeException) {
            false
        }
    }

    fun verify(
        keyReference: SigningKeyReference,
        canonicalPayload: ByteArray,
        signature: ByteArray
    ): Boolean {
        val keyPair = keys[keyReference] ?: return false
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(keyPair.public)
            verifier.update(canonicalPayload)
            verifier.verify(signature)
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        fun create(
            activeKeys: Set<String>,
            retiredKeys: Set<String> = emptySet()
        ): InMemoryEd25519SigningFixture {
            val all = activeKeys + retiredKeys
            val generator = KeyPairGenerator.getInstance("Ed25519")
            val pairs = all.associate { id ->
                SigningKeyReference(id) to generator.generateKeyPair()
            }
            return InMemoryEd25519SigningFixture(
                keys = pairs,
                retired = retiredKeys.mapTo(mutableSetOf()) { SigningKeyReference(it) }
            )
        }
    }
}
