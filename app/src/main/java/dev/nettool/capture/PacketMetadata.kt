package dev.nettool.capture

import java.net.InetAddress

/** Parses base IPv4/IPv6 TCP and UDP headers only. Fragmented/extension headers are unknown. */
data class PacketMetadata(val source: String, val destination: String, val sourcePort: Int, val destinationPort: Int, val protocol: Int) {
    companion object {
        fun parse(bytes: ByteArray): PacketMetadata? {
            if (bytes.isEmpty()) return null
            fun u(i: Int) = bytes[i].toInt() and 255
            fun word(i: Int) = (u(i) shl 8) or u(i + 1)
            val version = u(0) ushr 4
            val offset: Int
            val protocol: Int
            val source: ByteArray
            val destination: ByteArray
            when (version) {
                4 -> {
                    if (bytes.size < 20) return null
                    offset = (u(0) and 15) * 4
                    if (offset < 20 || (word(6) and 0x1fff) != 0) return null
                    protocol = u(9)
                    source = bytes.copyOfRange(12, 16)
                    destination = bytes.copyOfRange(16, 20)
                }
                6 -> {
                    if (bytes.size < 40) return null
                    offset = 40
                    protocol = u(6)
                    source = bytes.copyOfRange(8, 24)
                    destination = bytes.copyOfRange(24, 40)
                }
                else -> return null
            }
            if (protocol != 6 && protocol != 17) return null
            if (bytes.size < offset + if (protocol == 6) 20 else 8) return null
            return PacketMetadata(InetAddress.getByAddress(source).hostAddress!!,
                InetAddress.getByAddress(destination).hostAddress!!, word(offset), word(offset + 2), protocol)
        }
    }
}
