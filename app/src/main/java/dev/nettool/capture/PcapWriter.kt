package dev.nettool.capture

import java.io.Closeable
import java.io.File
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Classic PCAP, little endian, LINKTYPE_RAW (101), with explicit bounded storage. */
class PcapWriter(file: File, private val maxBytes: Long = 25L * 1024 * 1024) : Closeable {
    private val output = BufferedOutputStream(file.outputStream())
    var size: Long = 24
        private set
    init {
        output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0)
            .putInt(65535).putInt(101).array())
    }
    fun write(packet: ByteArray, timestampMillis: Long): Boolean {
        if (size + 16 + packet.size > maxBytes) return false
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((timestampMillis / 1000).toInt()).putInt(((timestampMillis % 1000) * 1000).toInt())
            .putInt(packet.size).putInt(packet.size).array()
        output.write(header)
        output.write(packet)
        size += 16 + packet.size
        return true
    }
    override fun close() = output.close()
}
