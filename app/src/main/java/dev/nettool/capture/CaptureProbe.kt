package dev.nettool.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProbeKind { HTTP, HTTPS, DNS, TCP }
data class ProbeRequest(val kind: ProbeKind, val target: String, val port: Int = 443,
    val dnsType: String = "A", val dnsServer: String = "", val timeoutMillis: Int = 8000, val headers:Map<String,String> = dev.nettool.core.HttpProfile.defaults) {
    fun validate() {
        dev.nettool.core.HttpProfile.validate(headers)
        require(timeoutMillis in 500..15000) { "Timeout permitido: 500 a 15000 ms" }
        require(target.isNotBlank() && target.length <= 2048) { "Informe um alvo de até 2048 caracteres" }
        require(target.none { it == '\r' || it == '\n' || it.code == 0 }) { "Alvo inválido" }
        when (kind) {
            ProbeKind.HTTP, ProbeKind.HTTPS -> {
                val url = target.toHttpUrl()
                require(url.scheme == kind.name.lowercase()) { "Use uma URL ${kind.name.lowercase()}://" }
                require(url.username.isEmpty() && url.password.isEmpty()) { "Não inclua credenciais na URL" }
            }
            ProbeKind.TCP -> {
                require(port in 1..65535) { "Porta TCP inválida" }
                require(target.length <= 253 && target.none { it.isWhitespace() || it == '/' }) { "Informe apenas domínio ou IP" }
            }
            ProbeKind.DNS -> {
                require(dnsType in setOf("A", "AAAA")) { "Tipo DNS: A ou AAAA" }
                CaptureDns.query(target, dnsType, 1)
                if (dnsServer.isNotBlank()) CaptureDns.numericAddress(dnsServer)
            }
        }
    }
}
data class ProbeResult(val kind: ProbeKind, val target: String, val success: Boolean,
    val summary: String, val durationMillis: Long, val details: Map<String, String> = emptyMap())

/** Explicit user probes, always bound to this app's VPN. No payload retained in results. */
object CaptureProbe {
    suspend fun run(): String = run(ProbeRequest(ProbeKind.HTTPS, "https://example.com/")).summary
    suspend fun run(request: ProbeRequest): ProbeResult = withContext(Dispatchers.IO) {
        request.validate()
        check(CaptureState.state.value.running) { "Inicie a captura antes do teste" }
        val vpn = CaptureService.captureNetwork() ?: error("Rede VPN ainda não está disponível")
        val began = System.nanoTime()
        val safeTarget = CaptureMasking.url(request.target)
        fun result(success: Boolean, summary: String, details: Map<String, String> = emptyMap()) = ProbeResult(
            request.kind, safeTarget, success, CaptureMasking.text(summary),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began), details.mapValues { CaptureMasking.text(it.value) })
        try {
            when (request.kind) {
                ProbeKind.HTTP, ProbeKind.HTTPS -> {
                    val timings = linkedMapOf<String, String>()
                    var dnsBegan = 0L
                    var connectBegan = 0L
                    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                        .socketFactory(vpn.socketFactory).dns(object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> = vpn.getAllByName(hostname).toList()
                        }).eventListener(object : EventListener() {
                            override fun dnsStart(call: Call, domainName: String) { dnsBegan = System.nanoTime() }
                            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                                timings["DNS ms"] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsBegan).toString()
                            }
                            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                                connectBegan = System.nanoTime(); timings["IP"] = inetSocketAddress.address?.hostAddress.orEmpty()
                            }
                            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                                timings["Conexão ms"] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectBegan).toString()
                            }
                        }).connectTimeout(request.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                        .readTimeout(request.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                        .callTimeout(request.timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                        .followRedirects(false).followSslRedirects(false).build()
                    try {
                        val call = client.newCall(Request.Builder().url(request.target)
                            .apply {request.headers.forEach {(name,value)->header(name,value)}}.build())
                        val received = await(call)
                        received.first.use { response ->
                            val sampleSize = received.second
                            val data = linkedMapOf("URL" to safeTarget, "Status" to response.code.toString(),
                                "Protocolo" to response.protocol.toString(), "Amostra bytes" to sampleSize.toString(),
                                "Content-Type" to (response.header("Content-Type") ?: "não informado"),
                                "Origem" to "Resposta do próprio cliente do app. Não é descriptografia TLS do PCAP")
                            data.putAll(timings)
                            response.headers.names().take(20).forEach { name ->
                                data["Header ${CaptureMasking.text(name)}"] = when (name.lowercase()) {
                                    "content-type", "content-length", "content-encoding", "server", "date", "connection", "cache-control" ->
                                        CaptureMasking.text(response.headers.values(name).joinToString()).take(256)
                                    "location" -> CaptureMasking.url(response.header(name).orEmpty())
                                    else -> "[valor mascarado]"
                                }
                            }
                            val contentType = response.header("Content-Type").orEmpty().lowercase()
                            data["Formato observado"] = when {
                                contentType.contains("html") -> "HTML declarado pelo servidor"
                                contentType.contains("json") -> "JSON declarado pelo servidor"
                                contentType.contains("text/") -> "Texto declarado pelo servidor"
                                else -> "Corpo não classificado; conteúdo não persistido"
                            }
                            response.header("Location")?.let { data["Redirect (não seguido)"] = CaptureMasking.url(it) }
                            response.handshake?.let { data["TLS"] = "${it.tlsVersion.javaName} · ${it.cipherSuite.javaName}" }
                            result(true, "${request.kind}: HTTP ${response.code}, $sampleSize bytes de amostra via VPN", data)
                        }
                    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
                ProbeKind.TCP -> {
                    val dnsStart = System.nanoTime()
                    val addresses = vpn.getAllByName(request.target)
                    require(addresses.isNotEmpty()) { "DNS não retornou IP" }
                    val dnsTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsStart)
                    val address = addresses.first()
                    val socket = vpn.socketFactory.createSocket()
                    try {
                        connect(socket, InetSocketAddress(address, request.port), request.timeoutMillis)
                        result(true, "TCP ${request.port}: conexão aceita via VPN", mapOf(
                            "IP" to address.hostAddress.orEmpty(), "DNS ms" to "$dnsTime", "Porta" to "${request.port}",
                            "Observação" to "Uma porta e um IP. TCP aceito não identifica o serviço"))
                    } finally { socket.close() }
                }
                ProbeKind.DNS -> {
                    val server = if (request.dnsServer.isBlank()) CaptureService.physicalDnsServers().firstOrNull()
                        ?: error("Wi-Fi não informou servidor DNS; informe um IP") else CaptureDns.numericAddress(request.dnsServer)
                    val id = java.security.SecureRandom().nextInt(65536)
                    val query = CaptureDns.query(request.target, request.dnsType, id)
                    val socket = DatagramSocket(null)
                    try {
                        vpn.bindSocket(socket)
                        socket.bind(InetSocketAddress(0))
                        socket.connect(server, 53)
                        socket.soTimeout = request.timeoutMillis
                        val answer = CaptureDns.answer(exchangeDns(socket, query), id)
                        result(answer.rcode == 0, "DNS ${request.dnsType}: RCODE ${answer.rcode}, ${answer.addresses.size} respostas via VPN",
                            mapOf("Servidor" to server.hostAddress.orEmpty(), "IPs" to answer.addresses.joinToString(),
                                "RCODE" to "${answer.rcode}", "Truncado" to "${answer.truncated}",
                                "Observação" to if (answer.truncated) "Resposta UDP truncada; sem fallback TCP automático" else "DNS não comprova host ativo"))
                    } finally { socket.close() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val outcome = when (error) {
                is SocketTimeoutException -> "timeout, resultado inconclusivo"
                is ConnectException -> if (generateSequence(error as Throwable?) { it.cause }.any {
                    (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ECONNREFUSED) ||
                        it.message?.contains("ECONNREFUSED") == true || it.message?.contains("Connection refused", true) == true
                }) "conexão recusada" else "erro ao conectar"
                is javax.net.ssl.SSLException -> "falha TLS; validação preservada"
                is UnknownHostException -> "falha DNS"
                else -> "falha ${error.javaClass.simpleName}"
            }
            result(false, "${request.kind}: $outcome", mapOf("Tipo de erro" to error.javaClass.simpleName))
        }
    }

    private suspend fun await(call: Call): Pair<Response, Int> = suspendCancellableCoroutine { continuation ->
        val received = java.util.concurrent.atomic.AtomicReference<Response?>()
        continuation.invokeOnCancellation { call.cancel(); received.get()?.close() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                received.set(response)
                try {
                    val sampleSize = response.peekBody(4096).bytes().size
                    if (continuation.isActive) continuation.resume(response to sampleSize) else response.close()
                } catch (e: Exception) {
                    response.close()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    private suspend fun connect(socket: Socket, address: InetSocketAddress, timeout: Int) = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        try { socket.connect(address, timeout); if (continuation.isActive) continuation.resume(Unit) }
        catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    private suspend fun exchangeDns(socket: DatagramSocket, query: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { socket.close() }
        try {
            socket.send(DatagramPacket(query, query.size))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            if (continuation.isActive) continuation.resume(response.data.copyOf(response.length))
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
}
