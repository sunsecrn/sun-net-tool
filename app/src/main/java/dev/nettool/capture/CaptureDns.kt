package dev.nettool.capture

import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.InetAddress

data class CaptureDnsAnswer(val rcode: Int, val truncated: Boolean, val addresses: List<String>)

/** Bounded UDP DNS codec for one A/AAAA question; compression loops and malformed lengths rejected. */
object CaptureDns {
    fun numericAddress(value: String): InetAddress {
        val ipv4 = value.split('.').let { parts -> parts.size == 4 && parts.all {
            it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255
        } }
        val ipv6 = ':' in value && value.matches(Regex("[0-9a-fA-F:.]+"))
        require(ipv4 || ipv6) { "Servidor DNS deve ser IP literal IPv4 ou IPv6" }
        return InetAddress.getByName(value)
    }
    fun query(hostname: String, type: String, id: Int): ByteArray {
        require(type == "A" || type == "AAAA") { "Tipo DNS inválido" }
        val name = IDN.toASCII(hostname.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
        require(name.length in 1..253 && name.none { it.isWhitespace() }) { "Nome DNS inválido" }
        val labels = name.split('.')
        require(labels.all { it.length in 1..63 }) { "Nome DNS inválido" }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        labels.forEach { out.write(it.length); out.write(it.toByteArray(Charsets.US_ASCII)) }
        out.write(byteArrayOf(0, 0, if (type == "A") 1 else 28, 0, 1))
        return out.toByteArray()
    }
    fun answer(bytes: ByteArray, expectedId: Int): CaptureDnsAnswer {
        require(bytes.size in 12..4096) { "Tamanho DNS inválido" }
        fun u16(i: Int): Int {
            require(i >= 0 && i + 2 <= bytes.size) { "DNS truncado" }
            return ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
        }
        require(u16(0) == expectedId && u16(2) and 0x8000 != 0) { "Resposta DNS não corresponde à consulta" }
        fun skipName(start: Int): Int {
            var at = start
            var end = -1
            var count = 0
            val visited = mutableSetOf<Int>()
            while (true) {
                require(at in bytes.indices && visited.add(at)) { "Compressão DNS inválida" }
                val size = bytes[at].toInt() and 255
                if (size == 0) return if (end >= 0) end else at + 1
                if (size and 0xc0 == 0xc0) {
                    if (end < 0) end = at + 2
                    at = u16(at) and 0x3fff
                } else {
                    require(size in 1..63 && at + size + 1 <= bytes.size) { "Rótulo DNS inválido" }
                    count += size + 1; require(count <= 255) { "Nome DNS longo" }
                    at += size + 1
                }
            }
        }
        val questions = u16(4)
        val answers = u16(6)
        require(questions <= 16 && answers <= 128) { "Resposta DNS excede limite" }
        var at = 12
        repeat(questions) { at = skipName(at); require(at + 4 <= bytes.size); at += 4 }
        val ips = mutableListOf<String>()
        repeat(answers) {
            at = skipName(at)
            val type = u16(at); val cls = u16(at + 2); val size = u16(at + 8)
            at += 10
            require(at + size <= bytes.size) { "Resposta DNS truncada" }
            if (cls == 1 && ((type == 1 && size == 4) || (type == 28 && size == 16)))
                ips += InetAddress.getByAddress(bytes.copyOfRange(at, at + size)).hostAddress.orEmpty()
            at += size
        }
        return CaptureDnsAnswer(u16(2) and 15, u16(2) and 0x0200 != 0, ips.distinct())
    }
}
