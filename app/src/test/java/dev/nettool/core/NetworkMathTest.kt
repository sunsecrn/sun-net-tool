package dev.nettool.core

import org.junit.Assert.*
import org.junit.Test

class NetworkMathTest {
    @Test fun prefixUsesActualNetwork() { assertEquals(listOf("10.1.2.5","10.1.2.6"), NetworkMath.targets("10.1.2.6/30")) }
    @Test fun pointToPointAndSingleAddress() { assertEquals(listOf("10.0.0.0","10.0.0.1"), NetworkMath.targets("10.0.0.1/31")); assertEquals(listOf("10.0.0.9"),NetworkMath.targets("10.0.0.9/32")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsLargeRange() { NetworkMath.targets("10.0.0.1/16") }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidAddress() { NetworkMath.targets("999.0.0.1/24") }
    @Test fun masksHeadersAndQueries() { assertEquals("[mascarado]", Redaction.header("Set-Cookie", "session=abc")); assertFalse(Redaction.text("token=abc123").contains("abc123")); assertFalse(Redaction.url("https://a.test/x?key=123").contains("123")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsDnsPointerLoop() { DnsWire.ptrAnswers(byteArrayOf(0,0,0,0,0,1,0,0,0,0,0,0,0xc0.toByte(),12,0,1,0,1)) }
    @Test fun readsPtrWithCompressedOwner() {
        val packet = byteArrayOf(0,1,0x81.toByte(),0x80.toByte(),0,1,0,1,0,0,0,0,1,120,0,0,12,0,1,0xc0.toByte(),12,0,12,0,1,0,0,0,1,0,6,4,104,111,115,116,0)
        assertEquals(listOf("host"), DnsWire.ptrAnswers(packet))
    }
}
