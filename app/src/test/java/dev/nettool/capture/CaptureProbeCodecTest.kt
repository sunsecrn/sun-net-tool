package dev.nettool.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureProbeCodecTest {
    @Test fun decodesCompressedDnsAnswerAndRejectsWrongIdOrCompressionLoop() {
        val query = CaptureDns.query("example.com", "A", 123)
        val response = query.copyOf(query.size + 16)
        response[2] = 0x81.toByte(); response[3] = 0x80.toByte(); response[7] = 1
        val answer = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 20, 30, 40)
        answer.copyInto(response, query.size)
        assertEquals(listOf("10.20.30.40"), CaptureDns.answer(response, 123).addresses)
        assertThrows(IllegalArgumentException::class.java) { CaptureDns.answer(response, 124) }
        response[12] = 0xc0.toByte(); response[13] = 12
        assertThrows(IllegalArgumentException::class.java) { CaptureDns.answer(response, 123) }
    }
    @Test fun reportsDnsRcodeAndTruncation() {
        val response = byteArrayOf(0, 1, 0x83.toByte(), 0x83.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val answer = CaptureDns.answer(response, 1)
        assertEquals(3, answer.rcode); assertTrue(answer.truncated)
    }
    @Test fun validatesProbeInputsWithoutNetworkAccess() {
        ProbeRequest(ProbeKind.HTTP, "http://192.168.1.1/").validate()
        ProbeRequest(ProbeKind.DNS, "example.com", dnsType = "AAAA").validate()
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest(ProbeKind.HTTPS, "http://example.com/").validate() }
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest(ProbeKind.HTTPS, "https://user:pass@example.com/").validate() }
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest(ProbeKind.TCP, "example.com", port = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest(ProbeKind.DNS, "example.com", dnsServer = "example.com").validate() }
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest(ProbeKind.DNS, "example.com", dnsServer = "dead").validate() }
    }
    @Test fun redactsHeaderJsonQueryAndMatchingValues() {
        val raw = "Authorization: Bearer short-value\r\nCookie: session=short\r\n{\"token\":\"abc\"} https://e.test/?q=private"
        val safe = CaptureMasking.text(raw)
        assertEquals(raw.length, safe.length)
        listOf("short-value", "session=short", "abc", "q=private").forEach { assertFalse(safe.contains(it)) }
        assertFalse(CaptureMasking.snippet("visible match secret", 8, 13).contains("match"))
    }
}
