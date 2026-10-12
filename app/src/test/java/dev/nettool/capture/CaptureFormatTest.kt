package dev.nettool.capture

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureFormatTest {
    @Test fun parsesUdpAndRejectsTruncationAndFragments() {
        val p = byteArrayOf(0x45,0,0,28,0,1,0,0,64,17,0,0,10,0,0,2,1,1,1,1,0x12,0x34,0,53,0,8,0,0)
        assertEquals(PacketMetadata("10.0.0.2", "1.1.1.1", 4660, 53, 17), PacketMetadata.parse(p))
        assertNull(PacketMetadata.parse(p.copyOf(24)))
        p[7] = 1
        assertNull(PacketMetadata.parse(p))
        assertNull(PacketMetadata.parse(byteArrayOf()))
    }
    @Test fun pcapRoundTripAndExactSizeLimit() {
        val file = File.createTempFile("nettool", ".pcap")
        try {
            PcapWriter(file, 43).use { writer ->
                assertTrue(writer.write(byteArrayOf(1,2,3), 1_234))
                assertFalse(writer.write(byteArrayOf(4), 1_235))
            }
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(43, b.remaining())
            assertEquals(0xa1b2c3d4.toInt(), b.int)
            b.position(20)
            assertEquals(101, b.int)
            assertEquals(1, b.int)
            assertEquals(234000, b.int)
            assertEquals(3, b.int)
            assertEquals(3, b.int)
            assertEquals(1, b.get().toInt())
        } finally { file.delete() }
    }
}
