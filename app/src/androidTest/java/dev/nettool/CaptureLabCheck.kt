package dev.nettool

import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.nettool.capture.*
import dev.nettool.core.NetworkMath
import dev.nettool.core.WifiConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Opt-in check against only the explicitly supplied owned lab host. No UI actions. */
suspend fun runCaptureLab(context: Context, wifi: WifiConnection, host: String): String {
    NetworkMath.ipv4(host)
    check(VpnService.prepare(context) == null) { "Consentimento VPN necessário: abra o app e autorize a captura" }
    check(!CaptureState.state.value.running) { "Encerre a captura atual antes do teste de laboratório" }
    val sessionId = "capture-lab-${System.currentTimeMillis()}"
    val start = Intent(context, CaptureService::class.java)
        .putExtra(CaptureService.EXTRA_SESSION_ID, sessionId)
        .putExtra(CaptureService.EXTRA_NETWORK_HANDLE, wifi.network.networkHandle)
    val probes = mutableListOf<ProbeResult>()
    var stopRequested = false
    var startRequested = false
    fun stop() {
        context.startService(Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        stopRequested = true
    }
    try {
        context.startForegroundService(start)
        startRequested = true
        withTimeout(8000) { CaptureState.state.first { it.running && it.sessionId == sessionId } }
        withTimeout(5000) { while (CaptureService.captureNetwork() == null) delay(100) }
        val diagnosticEvents=mutableListOf<dev.nettool.core.DiagnosticEvent>()
        val diagnosticEngine=dev.nettool.core.DiagnosticEngine(wifi.copy(network=requireNotNull(CaptureService.captureNetwork()))) {diagnosticEvents.add(it)}
        try {
            diagnosticEngine.http("http://$host/health")
            diagnosticEngine.scanPorts(host,listOf(80,2222))
            check(diagnosticEvents.any {it.kind=="http"&&it.message=="GET 200"}) {"Integrated HTTP through VPN failed"}
            check(diagnosticEvents.count {it.kind=="port"&&it.details["status"]=="OPEN"}==2) {"Integrated port scan through VPN failed"}
        } finally {diagnosticEngine.close()}
        probes += CaptureProbe.run(ProbeRequest(ProbeKind.HTTP, "http://$host/", timeoutMillis = 5000))
        check(probes.last().success && probes.last().details["Status"] == "200") { "HTTP do laboratório falhou: ${probes.last().summary}" }
        probes += CaptureProbe.run(ProbeRequest(ProbeKind.TCP, host, port = 2222, timeoutMillis = 4000))
        check(probes.last().success) { "TCP2222 do laboratório falhou: ${probes.last().summary}" }
        probes += CaptureProbe.run(ProbeRequest(ProbeKind.DNS, "example.invalid", timeoutMillis = 5000))
        check(probes.last().details.containsKey("RCODE")) { "DNS sem resposta decodificada: ${probes.last().summary}" }
        probes += CaptureProbe.run(ProbeRequest(ProbeKind.HTTPS, "https://$host:8443/", timeoutMillis = 5000))
        check(!probes.last().success && probes.last().summary.contains("falha TLS")) {
            "Certificado autoassinado deveria ser rejeitado: ${probes.last().summary}"
        }
        stop()
        val snapshot = withTimeout(5000) { CaptureState.state.first { !it.running && it.sessionId == sessionId && it.jsonPath != null } }
        check(snapshot.flows.any { it.protocol == "TCP" && it.destination == host && it.destinationPort == 80 && it.receivedBytes > 0 }) {
            "Captura não registrou resposta TCP80 do laboratório"
        }
        check(snapshot.flows.any { it.protocol == "UDP" && it.destinationPort == 53 && it.receivedBytes > 0 }) {
            "Captura não registrou ida/volta DNS UDP53"
        }
        val pcap = File(requireNotNull(snapshot.pcapPath))
        val literal = PcapSearch.search(pcap, SearchRequest("NetToolSyntheticLab"))
        val readable = if (literal.matches.isNotEmpty()) literal else PcapSearch.search(pcap, SearchRequest("HTTP/"))
        check(readable.matches.isNotEmpty()) { "Busca literal não encontrou HTTP claro no PCAP real" }
        val regex = PcapSearch.search(pcap, SearchRequest("(?i)(HTTP/[0-9.]+|Content-Type:)", regex = true))
        check(regex.matches.isNotEmpty()) { "Busca RE2 não encontrou cabeçalhos HTTP no PCAP real" }
        val result = JSONObject().put("sessionId", sessionId).put("packets", snapshot.packets)
            .put("bytes", snapshot.bytes).put("forwardingErrors", snapshot.forwardingErrors)
            .put("unsupportedPackets", snapshot.unsupportedPackets).put("pcapPath", snapshot.pcapPath)
            .put("metadataPath", snapshot.jsonPath).put("literalMatches", readable.matches.size)
            .put("regexMatches", regex.matches.size).put("tlsStreams", regex.tlsStreams)
            .put("probes", JSONArray(probes.map { probe -> JSONObject().put("kind", probe.kind.name)
                .put("target", probe.target).put("success", probe.success).put("summary", probe.summary)
                .put("durationMillis", probe.durationMillis).put("details", JSONObject(probe.details)) }))
            .put("matches", JSONArray((readable.matches + regex.matches).take(20).map { match -> JSONObject()
                .put("flow", match.flow).put("protocol", match.protocol).put("offset", match.offset).put("snippet", match.snippet) }))
        File(context.filesDir, "capture-lab-validation.json").writeText(result.toString(2))
        return "PASS capture HTTP200,TCP2222,DNS RCODE ${probes[2].details["RCODE"]},self-signed TLS rejection," +
            "PCAP text+RE2 search; packets=${snapshot.packets}; bytes=${snapshot.bytes}; session=$sessionId"
    } finally {
        if (startRequested && !stopRequested) stop()
        if (CaptureState.state.value.sessionId == sessionId && CaptureState.state.value.running) {
            runCatching { withTimeout(5000) { CaptureState.state.first { !it.running } } }
        }
    }
}
