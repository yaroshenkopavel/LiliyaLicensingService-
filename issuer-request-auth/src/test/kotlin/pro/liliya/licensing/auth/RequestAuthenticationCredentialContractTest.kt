package pro.liliya.licensing.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestAuthenticationCredentialContractTest {
    @Test
    fun credential_copies_input_and_output_and_renders_redacted() {
        val source = byteArrayOf(1, 2, 3)
        val credential = RequestAuthenticationCredential.of(source)

        source[0] = 9
        val first = credential.copyBytes()
        assertContentEquals(byteArrayOf(1, 2, 3), first)

        first[1] = 8
        assertContentEquals(byteArrayOf(1, 2, 3), credential.copyBytes())

        val rendered = credential.toString()
        assertTrue(rendered.contains("<redacted>"))
        assertFalse(rendered.contains("1, 2, 3"))
    }
}
