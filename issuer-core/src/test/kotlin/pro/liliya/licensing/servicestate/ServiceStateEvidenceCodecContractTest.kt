package pro.liliya.licensing.servicestate

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceStateEvidenceCodecContractTest {
    @Test
    fun canonical_payload_matches_frozen_core_v1_vector() {
        val payload = ServiceStateCanonicalCodec.encode(
            ServiceStateSecurityState(
                scope = ServiceStateScope(
                    productId = "liliya-pro",
                    subject = "subject"
                ),
                revocationEpoch = 4,
                replaySequence = 12
            )
        )

        assertEquals(
            "4c5353310000000a6c696c6979612d70726f000000077375626a656374" +
                "01000000000000000401000000000000000c00",
            payload.copyBytes().toHex()
        )
    }

    @Test
    fun authentication_transcript_matches_frozen_core_v1_vector() {
        val payload = ServiceStateCanonicalCodec.encode(
            ServiceStateSecurityState(
                scope = ServiceStateScope(
                    productId = "liliya-pro",
                    subject = "subject"
                ),
                revocationEpoch = 4,
                replaySequence = 12
            )
        )

        val transcript = ServiceStateAuthenticationTranscriptCodec.encode(
            protocolVersion = ServiceStateProtocolVersion(1),
            purpose = ServiceStateEvidencePurpose.SECURITY_STATE,
            profile = ServiceStateEvidenceProfile(
                "ECDSA-P256-SHA256-SERVICE-STATE-V1"
            ),
            signingKeyId = ServiceStateSigningKeyId("service-state-key-v1"),
            payload = payload
        )

        assertEquals(
            "4c53535400000000000000010000000e53454355524954595f5354415445" +
                "0000002245434453412d503235362d5348413235362d534552564943452d" +
                "53544154452d563100000014736572766963652d73746174652d6b65792d" +
                "7631000000304c5353310000000a6c696c6979612d70726f000000077375" +
                "626a65637401000000000000000401000000000000000c00",
            transcript.toHex()
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
