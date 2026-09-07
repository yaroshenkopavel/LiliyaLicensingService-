package pro.liliya.licensing.testkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TestSigningAdapterContractTest {
    @Test
    fun same_test_key_and_payload_are_deterministic() {
        val payload = "fixture".encodeToByteArray()
        val first = TestSigningAdapter.sign("test-key-1", payload)
        val second = TestSigningAdapter.sign("test-key-1", payload)

        assertContentEquals(first.testSignature, second.testSignature)
    }

    @Test
    fun non_test_key_identity_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            TestSigningAdapter.sign("production-key", "fixture".encodeToByteArray())
        }
    }
}
