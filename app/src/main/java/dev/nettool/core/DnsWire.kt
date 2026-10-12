package dev.nettool.core

/** Minimal bounded DNS decoder for PTR answers. Rejects malformed compression pointers. */
object DnsWire {
    fun ptrAnswers(bytes: ByteArray): List<String> {
        require(bytes.size in 12..65535) { "Tamanho DNS inválido" }
        fun u16(offset: Int): Int { require(offset >= 0 && offset + 1 < bytes.size); return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset+1].toInt() and 255) }
        fun name(start: Int): Pair<String, Int> {
            var position = start; var end = -1; val labels = mutableListOf<String>(); val visited = mutableSetOf<Int>(); var total = 0
            while (true) {
                require(position in bytes.indices && visited.add(position)) { "Compressão DNS inválida" }
                val length = bytes[position].toInt() and 255
                if (length == 0) { if (end < 0) end = position + 1; break }
                if (length and 192 == 192) { val pointer = u16(position) and 0x3fff; if (end < 0) end = position + 2; position = pointer; continue }
                require(length <= 63 && position + 1 + length <= bytes.size)
                total += length + 1; require(total <= 255)
                labels.add(bytes.copyOfRange(position+1, position+1+length).toString(Charsets.US_ASCII)); position += 1 + length
            }
            return labels.joinToString(".") to end
        }
        var position = 12
        repeat(u16(4)) { position = name(position).second; require(position+4 <= bytes.size); position += 4 }
        val answers = mutableListOf<String>()
        repeat(u16(6)) {
            position = name(position).second
            require(position + 10 <= bytes.size)
            val type = u16(position); val cls = u16(position+2); val length = u16(position+8); position += 10
            require(position + length <= bytes.size)
            if (type == 12 && cls == 1) { val decoded = name(position); require(decoded.second <= position+length); answers.add(decoded.first) }
            position += length
        }
        return answers
    }
}
