package dev.nettool.core
import org.junit.Assert.*
import org.junit.Test
class HttpProfileTest {
    @Test fun rejectsHeaderInjectionAndTransportHeaders() {
        assertThrows(IllegalArgumentException::class.java) {HttpProfile.validate(mapOf("User-Agent" to "test\r\nCookie: injected"))}
        assertThrows(IllegalArgumentException::class.java) {HttpProfile.validate(mapOf("Host" to "other.invalid"))}
        assertEquals(HttpProfile.browser,HttpProfile.validate(HttpProfile.browser))
    }
    @Test fun breaksNetworkIntoExplicitBatches() {
        assertEquals(listOf("10.1.0.0/24","10.1.1.0/24"),NetworkMath.batches("10.1.0.4/23"))
        assertEquals(listOf("192.0.2.0/24"),NetworkMath.batches("192.0.2.4/24"))
        assertEquals(16,NetworkMath.batches("10.0.0.1/8").size)
    }
}
