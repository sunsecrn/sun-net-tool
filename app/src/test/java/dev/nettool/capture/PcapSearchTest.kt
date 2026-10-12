package dev.nettool.capture

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapSearchTest {
    private fun packet(seq: Long, value: ByteArray, tcp: Boolean = true, port: Int = 80): ByteArray {
        val header = if (tcp) 40 else 28
        val b = ByteBuffer.allocate(header + value.size).order(ByteOrder.BIG_ENDIAN)
        b.put(0, 0x45); b.putShort(2, b.capacity().toShort()); b.put(8, 64); b.put(9, if (tcp) 6 else 17)
        b.put(12, 10); b.put(15, 2); b.put(16, 10); b.put(19, 1)
        b.putShort(20, 12345); b.putShort(22, port.toShort())
        if (tcp) { b.putInt(24, seq.toInt()); b.put(32, 0x50); b.put(33, 0x18) }
        else b.putShort(24, (8 + value.size).toShort())
        b.position(header); b.put(value)
        return b.array()
    }
    private fun capture(vararg packets: ByteArray): ByteArray {
        val file = File.createTempFile("capture-search", ".pcap")
        return try { PcapWriter(file).use { writer -> packets.forEach { writer.write(it, 1000) } }; file.readBytes() }
        finally { file.delete() }
    }
    private fun search(bytes: ByteArray, query: String, regex: Boolean = false) =
        PcapSearch.search(ByteArrayInputStream(bytes), SearchRequest(query, regex))

    @Test fun reassemblesOutOfOrderAndDeduplicatesRetransmissions() {
        val first = "GET / HTTP/1.1\r\nHost: example.com\r\nX-Name: hel".toByteArray()
        val next = "lo-world\r\n\r\n".toByteArray()
        val data = capture(packet(100L + first.size, next), packet(100, first), packet(100, first))
        val result = search(data, "hello-world")
        assertEquals(1, result.matches.size)
        assertEquals(0, result.gaps)
        assertFalse(result.matches[0].snippet.contains("hello-world"))
    }
    @Test fun gapsNeverCreateSyntheticMatchesAndConflictingOverlapsAreIgnored() {
        val gap = search(capture(packet(100, "sec".toByteArray()), packet(104, "ret".toByteArray())), "secret")
        assertEquals(1, gap.gaps); assertTrue(gap.matches.isEmpty())
        val conflict = search(capture(packet(100, "secret".toByteArray()), packet(102, "xxx".toByteArray())), "secret")
        assertEquals(1, conflict.conflictingStreams); assertTrue(conflict.matches.isEmpty())
    }
    @Test fun wrapsTcpSequenceNumbersAndDoesNotJoinUdpDatagrams() {
        val text = "hello-".toByteArray()
        val wrapped = search(capture(packet(0xfffffffdL, text), packet(3, "world".toByteArray())), "hello-world")
        assertEquals(1, wrapped.matches.size)
        val udp = search(capture(packet(0, "hello-".toByteArray(), false), packet(0, "world".toByteArray(), false)), "hello-world")
        assertTrue(udp.matches.isEmpty())
    }
    @Test fun skipsTlsAndMasksSensitiveContextBeforeSlicingSnippet() {
        val tls = search(capture(packet(1, byteArrayOf(22, 3, 3, 0, 6) + "secret".toByteArray(), port = 443)), "secret")
        assertEquals(1, tls.tlsStreams); assertTrue(tls.matches.isEmpty())
        val text = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=raw-session-value\r\n\r\n{\"password\":\"raw-pass\",\"status\":\"ready\"}".toByteArray()
        val result = search(capture(packet(1, text)), "ready")
        assertEquals(1, result.matches.size)
        val snippet = result.matches.single().snippet
        assertFalse(snippet.contains("raw-session-value")); assertFalse(snippet.contains("raw-pass")); assertFalse(snippet.contains("ready"))
    }
    @Test(timeout = 2500) fun regexUsesLinearEngineAndRejectsBackreferences() {
        val pcap = capture(packet(1, ("a".repeat(50_000) + "!").toByteArray()))
        assertTrue(search(pcap, "(a+)+$", true).matches.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { search(pcap, "(a)\\1", true) }
    }
    @Test fun capsResultsAndRejectsCorruptContainer() {
        val result = search(capture(packet(1, "ab ".repeat(100).toByteArray())), "ab")
        assertEquals(50, result.matches.size); assertTrue(result.truncated)
        assertThrows(IllegalArgumentException::class.java) { search(ByteArray(24), "x") }
    }
    @Test fun utf8OffsetsRemainByteOffsets() {
        val result = search(capture(packet(1, "ação ready".toByteArray())), "ready")
        assertEquals("ação ".toByteArray().size.toLong(), result.matches.single().offset)
    }
}
