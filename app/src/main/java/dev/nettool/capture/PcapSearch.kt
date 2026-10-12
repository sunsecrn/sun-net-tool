package dev.nettool.capture

import com.google.re2j.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

data class SearchRequest(val query: String, val regex: Boolean = false, val caseSensitive: Boolean = false)
data class SearchMatch(val flow: String, val protocol: String, val offset: Long, val snippet: String,
    val timestampMillis: Long = 0)
data class SearchReport(val matches: List<SearchMatch>, val packetsRead: Int, val streamsSearched: Int,
    val gaps: Int, val tlsStreams: Int, val binaryStreams: Int, val conflictingStreams: Int,
    val truncated: Boolean, val warnings: List<String> = emptyList()) {
    val summary: String get() = "${matches.size} correspondências mascaradas · $streamsSearched trechos de texto · " +
        "$gaps lacunas · $tlsStreams fluxos TLS opacos · $conflictingStreams conflitos" + if (truncated) " · limite atingido" else ""
}

/** Bounded offline search. No packets, patterns or unmasked content are written to disk. */
object PcapSearch {
    const val MAX_FILE_BYTES = 25L * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_STREAM_BYTES = 64 * 1024
    const val MAX_STREAMS = 128
    const val MAX_MATCHES = 50
    private const val MAX_PACKETS = 100_000
    private data class Segment(val sequence: Long, val payload: ByteArray, val time: Long)
    private class Stream(val label: String, val protocol: String, val tlsPort: Boolean, val anchor: Long,
        var syn: Long? = null) {
        val segments = mutableListOf<Segment>()
        var size = 0
    }
    private data class Chunk(val offset: Long, val bytes: ByteArray, val time: Long)
    private data class Reassembled(val chunks: List<Chunk>, val gaps: Int, val conflict: Boolean)

    suspend fun search(file: File, request: SearchRequest): SearchReport = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        file.inputStream().buffered().use { search(it, request) { context.ensureActive() } }
    }

    /** Public for fixture testing. Reads classic PCAP RAW only, never arbitrary container formats. */
    fun search(input: InputStream, request: SearchRequest, checkCancelled: () -> Unit = {}): SearchReport {
        require(request.query.length in 1..256) { "Busca: informe de 1 a 256 caracteres" }
        val expression = if (request.regex) request.query else Pattern.quote(request.query)
        val pattern = try { Pattern.compile(expression, if (request.caseSensitive) 0 else Pattern.CASE_INSENSITIVE) }
        catch (_: com.google.re2j.PatternSyntaxException) { throw IllegalArgumentException("Regex inválida ou recurso não suportado pelo RE2") }
        val startTime = System.nanoTime()
        val reader = DataInputStream(input)
        val header = ByteArray(24)
        try { reader.readFully(header) } catch (_: java.io.EOFException) { throw IllegalArgumentException("Cabeçalho PCAP incompleto") }
        val magic = header.take(4).map { it.toInt() and 255 }
        val little = magic == listOf(0xd4, 0xc3, 0xb2, 0xa1) || magic == listOf(0x4d, 0x3c, 0xb2, 0xa1)
        val big = magic == listOf(0xa1, 0xb2, 0xc3, 0xd4) || magic == listOf(0xa1, 0xb2, 0x3c, 0x4d)
        require(little || big) { "Formato PCAP clássico obrigatório" }
        val nanos = magic == listOf(0x4d, 0x3c, 0xb2, 0xa1) || magic == listOf(0xa1, 0xb2, 0x3c, 0x4d)
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val h = ByteBuffer.wrap(header).order(order)
        require(h.getShort(4).toInt() == 2 && h.getShort(6).toInt() == 4 && h.getInt(20) == 101) { "PCAP RAW v2.4 obrigatório" }
        val streams = mutableListOf<Stream>()
        val currentStreams = mutableMapOf<String, Stream>()
        var fileBytes = 24L
        var payloadBytes = 0
        var packets = 0
        var truncated = false
        var skipped = 0
        fun budgetExpired() = System.nanoTime() - startTime > 10_000_000_000L
        while (packets < MAX_PACKETS && fileBytes + 16 <= MAX_FILE_BYTES) {
            checkCancelled()
            if (budgetExpired()) { truncated = true; break }
            val first = reader.read()
            if (first < 0) break
            val record = ByteArray(16); record[0] = first.toByte()
            try { reader.readFully(record, 1, 15) } catch (_: java.io.EOFException) { truncated = true; break }
            val metadata = ByteBuffer.wrap(record).order(order)
            val seconds = metadata.int.toLong() and 0xffffffffL
            val fractional = metadata.int.toLong() and 0xffffffffL
            val size = metadata.int
            val original = metadata.int.toLong() and 0xffffffffL
            require(size in 0..65535) { "Tamanho de pacote PCAP inválido" }
            if (fileBytes + 16 + size > MAX_FILE_BYTES) { truncated = true; break }
            val packet = ByteArray(size)
            try { reader.readFully(packet) } catch (_: java.io.EOFException) { truncated = true; break }
            fileBytes += 16 + size; packets++
            if (original > size) { truncated = true; continue }
            val parsed = payload(packet)
            if (parsed == null) { skipped++; continue }
            val time = seconds * 1000 + fractional / if (nanos) 1_000_000 else 1000
            val info = parsed.metadata
            val label = "${info.source}:${info.sourcePort} > ${info.destination}:${info.destinationPort}"
            val key = "${info.protocol}:$label"
            val synSequence = if (parsed.syn) parsed.sequence else null
            var stream = currentStreams[key]
            val freshTcp = parsed.syn && stream != null && stream.syn != synSequence && (stream.segments.isNotEmpty() || stream.syn != null)
            // UDP datagrams never share a searchable byte stream.
            if (stream == null || freshTcp || info.protocol == 17) {
                if (streams.size >= MAX_STREAMS) { truncated = true; continue }
                stream = Stream(label, if (info.protocol == 6) "TCP" else "UDP",
                    info.sourcePort in setOf(443, 8443, 853) || info.destinationPort in setOf(443, 8443, 853),
                    parsed.sequence, synSequence)
                streams += stream
                currentStreams[key] = stream
            }
            if (parsed.syn) stream.syn = synSequence
            if (parsed.bytes.isEmpty()) continue
            val available = minOf(MAX_STREAM_BYTES - stream.size, MAX_PAYLOAD_BYTES - payloadBytes)
            if (available <= 0) { truncated = true; continue }
            val accepted = parsed.bytes.copyOf(minOf(available, parsed.bytes.size))
            if (accepted.size < parsed.bytes.size) truncated = true
            val offset = if (info.protocol == 17) 0L else unwrap(parsed.sequence, stream.anchor)
            stream.segments += Segment(offset, accepted, time)
            stream.size += accepted.size; payloadBytes += accepted.size
        }
        if (packets == MAX_PACKETS || fileBytes + 16 > MAX_FILE_BYTES) truncated = true
        val matches = mutableListOf<SearchMatch>()
        var gaps = 0; var tls = 0; var binary = 0; var conflicts = 0; var searched = 0
        for (stream in streams) {
            checkCancelled()
            if (budgetExpired()) { truncated = true; break }
            val merged = reassemble(stream.segments)
            gaps += merged.gaps
            if (merged.conflict) { conflicts++; continue }
            var countedTls = false
            for (chunk in merged.chunks) {
                if (isTls(chunk.bytes)) { if (!countedTls) { tls++; countedTls = true }; continue }
                val text = decodeText(chunk.bytes)
                if (text == null) {
                    if (stream.tlsPort) { if (!countedTls) { tls++; countedTls = true } } else binary++
                    continue
                }
                // A known TLS flow remains opaque even if a later captured chunk looks printable.
                if (countedTls) continue
                searched++
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    checkCancelled()
                    matches += SearchMatch(stream.label, stream.protocol, chunk.offset + text.substring(0, matcher.start()).toByteArray(Charsets.UTF_8).size,
                        CaptureMasking.snippet(text, matcher.start(), matcher.end()), chunk.time)
                    if (matches.size >= MAX_MATCHES) { truncated = true; break }
                }
                if (matches.size >= MAX_MATCHES) break
            }
            if (matches.size >= MAX_MATCHES) break
        }
        val warnings = mutableListOf("Busca apenas em texto claro. TLS não é descriptografado; conteúdo binário/comprimido não é pesquisado.",
            "Correspondências e valores sensíveis são mascarados. Ausência de resultado não prova ausência do conteúdo.")
        if (skipped > 0) warnings += "$skipped pacotes sem payload TCP/UDP básico pesquisável"
        if (gaps > 0) warnings += "Busca não atravessa lacunas TCP; fluxo pode começar no meio de uma conexão"
        if (conflicts > 0) warnings += "Fluxos com retransmissões conflitantes foram ignorados"
        if (truncated) warnings += "Limites: 25 MiB de PCAP, 2 MiB de payload, 64 KiB por fluxo, 128 fluxos, 50 resultados, 10 segundos"
        return SearchReport(matches, packets, searched, gaps, tls, binary, conflicts, truncated, warnings)
    }

    private data class Payload(val metadata: PacketMetadata, val sequence: Long, val syn: Boolean, val bytes: ByteArray)
    private fun payload(packet: ByteArray): Payload? {
        val metadata = PacketMetadata.parse(packet) ?: return null
        fun u(i: Int) = packet[i].toInt() and 255
        fun word(i: Int) = (u(i) shl 8) or u(i + 1)
        val ipv4 = u(0) ushr 4 == 4
        val ipHeader = if (ipv4) (u(0) and 15) * 4 else 40
        if (ipv4 && word(6) and 0x3fff != 0) return null
        val total = if (ipv4) word(2) else 40 + word(4)
        if (total > packet.size || total < ipHeader) return null
        val tcp = metadata.protocol == 6
        val transport = if (tcp) (u(ipHeader + 12) ushr 4) * 4 else 8
        if (transport < if (tcp) 20 else 8) return null
        val end = if (tcp) total else minOf(total, ipHeader + word(ipHeader + 4))
        if (ipHeader + transport > end) return null
        val syn = tcp && u(ipHeader + 13) and 2 != 0
        val seq = if (tcp) (0 until 4).fold(0L) { sum, n -> (sum shl 8) or u(ipHeader + 4 + n).toLong() } else 0L
        return Payload(metadata, (seq + if (syn) 1 else 0) and 0xffffffffL, syn, packet.copyOfRange(ipHeader + transport, end))
    }
    private fun unwrap(sequence: Long, anchor: Long): Long = ((sequence - anchor + 0x80000000L) and 0xffffffffL) - 0x80000000L
    private fun reassemble(segments: List<Segment>): Reassembled {
        if (segments.isEmpty()) return Reassembled(emptyList(), 0, false)
        val ordered = segments.sortedBy { it.sequence }
        val base = ordered.first().sequence
        val chunks = mutableListOf<Chunk>()
        var start = base; var expected = base; var time = ordered.first().time
        var data = ByteArray(MAX_STREAM_BYTES)
        var size = 0
        var gaps = 0
        for (segment in ordered) {
            if (segment.sequence > expected) {
                chunks += Chunk(start - base, data.copyOf(size), time)
                gaps++; start = segment.sequence; expected = start; time = segment.time; size = 0
            }
            val overlap = (expected - segment.sequence).coerceAtMost(segment.payload.size.toLong()).toInt()
            if (overlap > 0) {
                val at = (segment.sequence - start).toInt()
                if (at < 0 || (0 until overlap).any { data[at + it] != segment.payload[it] })
                    return Reassembled(emptyList(), gaps, true)
            }
            if (overlap < segment.payload.size) {
                segment.payload.copyInto(data, size, overlap)
                size += segment.payload.size - overlap
                expected = segment.sequence + segment.payload.size
            }
        }
        chunks += Chunk(start - base, data.copyOf(size), time)
        return Reassembled(chunks, gaps, false)
    }
    private fun isTls(bytes: ByteArray): Boolean = bytes.size >= 3 && (bytes[0].toInt() and 255) in 20..23 &&
        bytes[1].toInt() == 3 && (bytes[2].toInt() and 255) in 0..4
    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { return null }
        if (text.count { !it.isISOControl() || it in "\r\n\t" }.toDouble() / text.length < 0.95) return null
        return text
    }
}
