package dev.nettool.core

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DiagnosticEvent(val kind: String, val target: String, val message: String, val details: Map<String, String> = emptyMap(), val timeMillis: Long = System.currentTimeMillis())
enum class PortStatus { OPEN, REFUSED, TIMEOUT, ERROR }
data class PortResult(val host: String, val port: Int, val status: PortStatus, val elapsedMs: Long)

class DiagnosticEngine(private val connection: WifiConnection, private val httpHeaders:Map<String,String> = HttpProfile.defaults, private val onEvent: (DiagnosticEvent) -> Unit) : AutoCloseable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private fun emit(kind: String, target: String, message: String, details: Map<String, String> = emptyMap()) = onEvent(DiagnosticEvent(kind, if (target.startsWith("http://") || target.startsWith("https://")) HttpExtraction.safeUrl(target) else Redaction.url(target), Redaction.text(message), details.mapValues { Redaction.text(it.value) }))
    override fun close() { closed = true; sockets.forEach { runCatching { it.close() } }; calls.forEach { it.cancel() } }
    private fun checkOpen() { check(!closed) { "Investigação encerrada ou Wi-Fi desconectado" } }
    private suspend fun <T> socketWork(block: (Socket) -> T): T = withContext(Dispatchers.IO) {
        checkOpen()
        val socket = connection.network.socketFactory.createSocket()
        sockets.add(socket)
        if (closed) socket.close()
        // A cancellation watcher runs independently of the blocking connect/read.
        coroutineScope {
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { socket.close() } } }
            try { ensureActive(); block(socket) } finally { socket.close(); sockets.remove(socket); watcher.cancel() }
        }
    }
    private fun numeric(host: String): InetAddress = requireNotNull(HttpExtraction.literalAddress(host)) { "IP inválido" }
    private suspend fun addresses(host: String): List<InetAddress> {
        HttpExtraction.literalAddress(host)?.let { return listOf(it) }
        require(!(host.contains('.') && host.all { it.isDigit() || it == '.' })) { "IPv4 inválido" }
        require(host.isNotBlank() && !host.contains("://") && !host.any(Char::isWhitespace)) { "Informe apenas IP ou domínio" }
        val values = coroutineScope { listOf(async { dns(host, "A") }, async { dns(host, "AAAA") }).awaitAll().flatten() }
        return values.mapNotNull(HttpExtraction::literalAddress).distinctBy { it.hostAddress }
    }
    suspend fun scanPorts(host: String, ports: List<Int>): List<PortResult> = coroutineScope {
        checkOpen()
        require(ports.size in 1..65535 && ports.all { it in 1..65535 }) { "Escolha portas entre 1 e 65535" }
        val resolved = addresses(host)
        require(resolved.isNotEmpty()) { "Nenhum endereço resolvido para o alvo" }
        val address = resolved.first()
        val selected = ports.distinct()
        emit("scan-plan", address.hostAddress.orEmpty(), "${selected.size} portas TCP, até 16 conexões simultâneas", mapOf("parent" to host, "total" to "${selected.size}", "selectedAddress" to address.hostAddress.orEmpty(), "otherAddresses" to resolved.drop(1).mapNotNull { it.hostAddress }.joinToString(), "coverage" to "Somente o primeiro IP resolvido; os demais estão no inventário"))
        val cursor = java.util.concurrent.atomic.AtomicInteger()
        val processed = java.util.concurrent.atomic.AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<PortResult>())
        List(minOf(16, selected.size)) { async {
            while (true) {
                ensureActive()
                val index = cursor.getAndIncrement(); if (index >= selected.size) break
                val result = probe(address, selected[index]); results.add(result)
                synchronized(processed) { emit("scan-progress", address.hostAddress.orEmpty(), "Portas processadas", mapOf("processed" to "${processed.incrementAndGet()}", "total" to "${selected.size}", "status" to result.status.name, "port" to "${result.port}")) }
            }
        } }.awaitAll()
        val counts = PortStatus.entries.associate { it.name.lowercase() to results.count { result -> result.status == it }.toString() }
        emit("scan-complete", address.hostAddress.orEmpty(), "${results.size} portas: ${counts["open"]} abertas, ${counts["refused"]} recusadas, ${counts["timeout"]} sem resposta, ${counts["error"]} erros", counts + mapOf("processed" to "${results.size}", "total" to "${selected.size}", "parent" to host))
        results.sortedBy { it.port }
    }
    private suspend fun probe(address: InetAddress, port: Int): PortResult {
        val start = System.nanoTime()
        var failure: Map<String, String> = emptyMap()
        val status = try { socketWork { it.connect(InetSocketAddress(address, port), 900) }; PortStatus.OPEN }
        catch (e: CancellationException) { throw e }
        catch (e: SocketTimeoutException) { failure = mapOf("errorType" to "SocketTimeoutException", "cause" to "Connect deadline exceeded"); PortStatus.TIMEOUT }
        catch (e: IOException) {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
            val errno = chain.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            val reason = when (errno) { OsConstants.ECONNREFUSED -> "ECONNREFUSED"; OsConstants.ENETUNREACH -> "ENETUNREACH"; OsConstants.EHOSTUNREACH -> "EHOSTUNREACH"; OsConstants.EACCES -> "EACCES"; OsConstants.ETIMEDOUT -> "ETIMEDOUT"; else -> "IO_ERROR" }
            failure = mapOf("errorType" to e.javaClass.simpleName, "cause" to reason) + if(errno != null) mapOf("errno" to "$errno") else emptyMap()
            when(errno) { OsConstants.ECONNREFUSED -> PortStatus.REFUSED; OsConstants.ETIMEDOUT -> PortStatus.TIMEOUT; else -> PortStatus.ERROR }
        }
        val result = PortResult(address.hostAddress.orEmpty(), port, status, (System.nanoTime() - start) / 1_000_000)
        emit("port", result.host, "$port: $status", mapOf("port" to "$port", "status" to status.name, "tcpMs" to "${result.elapsedMs}", "source" to "TCP connect na rede selecionada") + failure)
        return result
    }
    suspend fun discover(cidr: String, ports: List<Int> = listOf(80, 443, 22)) = coroutineScope {
        require(ports.size in 1..1000 && ports.all { it in 1..65535 })
        val targets = NetworkMath.targets(cidr)
        val semaphore = Semaphore(16)
        val processed = java.util.concurrent.atomic.AtomicInteger()
        targets.map { host -> async { semaphore.withPermit {
            val results = mutableListOf<PortResult>()
            for(port in ports) {val result=probe(numeric(host),port);results.add(result);if(result.status in setOf(PortStatus.OPEN,PortStatus.REFUSED)) break}
            val present = results.any { it.status == PortStatus.OPEN || it.status == PortStatus.REFUSED }
            emit("host", host, if (present) "Resposta TCP observada" else "Sem evidência de presença, resultado inconclusivo", mapOf("present" to "$present", "source" to "TCP probe", "testedPorts" to results.joinToString {"${it.port}"}, "plannedPorts" to "${ports.size}", "coverage" to "Descoberta encerra no primeiro sinal de presença; não é scan completo", "processed" to "${processed.incrementAndGet()}", "total" to "${targets.size}"))
        } } }.awaitAll()
    }
    suspend fun inspectDns(targets:List<String>) {
        connection.dnsServers.forEach {emit("address",it,"Servidor DNS informado pela rede",mapOf("source" to "Configuração Wi-Fi","role" to "DNS"))}
        connection.gateways.forEach {emit("address",it,"Gateway informado pela rede",mapOf("source" to "Rota padrão","role" to "gateway"))}
        for(ip in (connection.gateways+connection.dnsServers+targets).distinct().filter {HttpExtraction.literalAddress(it)!=null}.take(32)) {
            val names=dns(ip,"PTR")
            names.take(4).forEach {name->dns(name,"A");dns(name,"AAAA")}
        }
        for(name in connection.domains.take(8)) {emit("domain",name,"Sufixo DNS informado pela rede",mapOf("source" to "Configuração Wi-Fi"));dns(name,"A")}
    }
    suspend fun identifyService(host:String,port:Int) {
        suspend fun attempt(label:String, action:suspend ()->Unit):Boolean = try {action();true} catch(e:CancellationException) {throw e} catch(e:Exception) {emit("error",host,"$label :$port: ${e.javaClass.simpleName}",mapOf("port" to "$port","source" to "Identificação de serviço"));false}
        if(port in setOf(22,2222)) {attempt("SSH") {ssh(host,port)};return}
        if(port in setOf(443,8443,9443,465,993,995,636,853)) {
            val ok=attempt("TLS") {tls(host,port)}
            if(ok && port in setOf(443,8443,9443)) attempt("HTTPS") {http("https://${if(host.contains(':')) "[$host]" else host}:$port/")}
        } else if(port in setOf(80,81,8000,8008,8080,8081,8888,3000,5000,9000)) attempt("HTTP") {http("http://${if(host.contains(':')) "[$host]" else host}:$port/")}
        else emit("service-check",host,"Porta aberta; identificação manual necessária",mapOf("port" to "$port"))
    }
    suspend fun checkPortal(url:String="http://connectivitycheck.gstatic.com/generate_204") {
        emit("portal","Wi-Fi","Verificação de portal iniciada",mapOf("androidPortal" to "${connection.captivePortal}","validated" to "${connection.validated}","expectedStatus" to "204"))
        http(url,connectivityProbe=true)
    }
    // rawQuery accepts DNS wire types; Android's IntDef only lists A/AAAA, omitting PTR (12).
    @android.annotation.SuppressLint("WrongConstant")
    suspend fun dns(name: String, type: String = "A"): List<String> {
        emit("stage-progress",name,"Consultando DNS $type")
        checkOpen()
        require(type in setOf("A", "AAAA", "PTR"))
        val queryType = if(type == "A" && HttpExtraction.literalAddress(name) != null) "PTR" else type
        val start = System.nanoTime()
        return try {
            val result = withTimeout(6000) { suspendCancellableCoroutine<List<String>> { continuation ->
                val signal = CancellationSignal(); continuation.invokeOnCancellation { signal.cancel() }
                val resolver = DnsResolver.getInstance(); val executor = Executor { it.run() }
                if (queryType == "PTR") {
                    val bytes = numeric(name).address
                    val reverse = if (bytes.size == 4) bytes.reversed().joinToString(".") { "${it.toInt() and 255}" } + ".in-addr.arpa" else bytes.joinToString("") { "%02x".format(it.toInt() and 255) }.reversed().map { "$it" }.joinToString(".") + ".ip6.arpa"
                    resolver.rawQuery(connection.network, reverse, DnsResolver.CLASS_IN, 12, DnsResolver.FLAG_EMPTY, executor, signal, object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) { if (continuation.isActive) { emit("dns", name, "PTR RCODE $rcode", mapOf("rcode" to "$rcode", "type" to queryType)); runCatching { if (rcode == 0) DnsWire.ptrAnswers(answer) else emptyList() }.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) }) } }
                        override fun onError(error: DnsResolver.DnsException) { if (continuation.isActive) continuation.resumeWithException(error) }
                    })
                } else resolver.query(connection.network, name, if (queryType == "A") DnsResolver.TYPE_A else DnsResolver.TYPE_AAAA, DnsResolver.FLAG_EMPTY, executor, signal, object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) { if (continuation.isActive) { emit("dns", name, "$queryType RCODE $rcode", mapOf("rcode" to "$rcode", "type" to queryType)); continuation.resume(answer.mapNotNull { it.hostAddress }) } }
                    override fun onError(error: DnsResolver.DnsException) { if (continuation.isActive) continuation.resumeWithException(error) }
                })
            } }
            emit("dns", name, "$queryType: ${result.joinToString().ifEmpty { "sem respostas" }}", mapOf("queryType" to queryType,"answers" to result.joinToString(),"configuredServers" to connection.dnsServers.joinToString(),"dnsMs" to "${(System.nanoTime()-start)/1_000_000}", "source" to "DnsResolver na rede selecionada", "meaning" to "Resposta DNS não comprova presença"))
            result.forEach { value -> emit(if (queryType == "PTR") "domain" else "address", value, "Observado em resposta $queryType de $name", mapOf("source" to "DNS $queryType", "parent" to name, "meaning" to "DNS não comprova presença")) }
            result
        } catch (e: TimeoutCancellationException) { emit("dns-error", name, "$queryType: limite de 6 segundos atingido"); emptyList() } catch (e: CancellationException) { throw e } catch (e: Exception) { emit("dns-error", name, "$queryType: ${e.javaClass.simpleName}"); emptyList() }
    }
    private data class HttpPage(val url: HttpUrl, val code: Int, val location: String?, val scripts: List<String>)
    private class HttpMetrics : EventListener() {
        val values = ConcurrentHashMap<String, String>()
        var dnsStart = 0L; var connectStart = 0L; var tlsStart = 0L; var tlsDuration = 0L
        override fun dnsStart(call: Call, domainName: String) { dnsStart = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { values["dnsMs"] = "${(System.nanoTime()-dnsStart)/1_000_000}"; values["resolvedIps"] = inetAddressList.mapNotNull { it.hostAddress }.joinToString() }
        override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) { connectStart = System.nanoTime(); tlsDuration = 0L }
        override fun secureConnectStart(call: Call) { tlsStart = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsDuration = System.nanoTime()-tlsStart; values["tlsMs"] = "${tlsDuration/1_000_000}" }
        override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { values["tcpMs"] = "${(System.nanoTime()-connectStart-tlsDuration)/1_000_000}" }
        override fun connectionAcquired(call: Call, connection: Connection) { values["remoteIp"] = connection.route().socketAddress.address.hostAddress.orEmpty(); values["connectionReused"] = "${connectStart == 0L}" }
    }
    suspend fun http(url: String, includeScripts: Boolean = false, connectivityProbe:Boolean = false) = withContext(Dispatchers.IO) {
        checkOpen()
        var current = url.toHttpUrl()
        require(current.username.isEmpty() && current.password.isEmpty()) { "Não inclua credenciais na URL" }
        val metrics = ConcurrentHashMap<Call, HttpMetrics>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListenerFactory { call -> HttpMetrics().also { metrics[call] = it } }
            .socketFactory(connection.network.socketFactory).dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> { checkOpen(); return connection.network.getAllByName(hostname).toList() } })
            .followRedirects(false).followSslRedirects(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
        val seen = mutableSetOf<String>()
        val emitted = mutableSetOf<String>()
        suspend fun request(target: HttpUrl, hop: Int, script: Boolean): HttpPage = coroutineScope {
            ensureActive(); checkOpen()
            emit("stage-progress",target.toString(),"Aguardando resposta HTTP (limite 12 s)")
            val call = client.newCall(Request.Builder().url(target).apply {HttpProfile.validate(httpHeaders).forEach {(name,value)->header(name,value)}}.build())
            calls.add(call)
            if (closed) call.cancel()
            val start = System.nanoTime()
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    val bytes = response.peekBody(65537).bytes()
                    val bounded = bytes.copyOf(minOf(bytes.size, 65536))
                    if(connectivityProbe && !script) emit("portal",target.toString(),if(response.code==204) "Resposta esperada de conectividade; não comprova ausência de portal" else "Resposta diferente do esperado; possível portal ou proxy",mapOf("status" to "${response.code}","location" to response.header("Location").orEmpty(),"source" to "Teste HTTP de conectividade"))
                    val contentType = response.header("Content-Type").orEmpty()
                    val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val content = if(HttpExtraction.isTextual(contentType)) bounded.toString(charset) else ""
                    var headerBudget = 32768
                    var headersTruncated = response.headers.names().size > 80
                    val rawHeaders = response.headers.names().take(80).associateWith { name ->
                        val raw = response.headers.values(name).joinToString()
                        val value = raw.take(minOf(4096, headerBudget)); headerBudget -= value.length
                        if(value.length < raw.length) headersTruncated = true
                        value
                    }
                    val headers = rawHeaders.mapValues { (name, value) ->
                        HttpExtraction.safeHeader(name, if(name.equals("Location", true)) target.resolve(value)?.toString() ?: value else value)
                    }
                    val measured = metrics[call]?.values?.toMap().orEmpty()
                    val ip = measured["remoteIp"].orEmpty()
                    measured["resolvedIps"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { resolved -> emit("address", resolved, "Resolvido para HTTP", mapOf("source" to "DNS HTTP na rede selecionada", "parent" to target.host)) }
                    emit("http", target.toString(), "GET ${response.code}", mapOf("pageTitle" to (if(contentType.contains("html",true)) org.jsoup.Jsoup.parse(content).title().take(200) else ""),"server" to response.header("Server").orEmpty(),"httpMs" to "${(System.nanoTime()-start)/1_000_000}", "headers" to headers.toString(), "headersTruncated" to "$headersTruncated", "status" to "${response.code}", "tls" to (response.handshake?.tlsVersion?.javaName ?: "HTTP claro"), "bodyBytes" to "${bounded.size}", "truncated" to "${bytes.size > 65536}", "hop" to "$hop", "script" to "$script") + measured + headers.mapKeys {"responseHeader.${it.key}"})
                    if(ip.isNotEmpty()) emit("service", ip, "${if(target.isHttps) "HTTPS" else "HTTP"} observado: ${response.code}", mapOf("address" to ip, "port" to "${target.port}", "type" to if(target.isHttps) "https" else "http", "hostname" to target.host, "protocolObserved" to "true", "protocol" to response.protocol.toString(), "source" to "Resposta HTTP recebida", "parent" to HttpExtraction.safeUrl(target.toString())))
                    SecurityAnalysis.headers(target.toString(),rawHeaders,response.headers.values("Set-Cookie"),!headersTruncated).forEach {finding->
                        emit(finding.kind,target.toString(),finding.title,finding.details+mapOf("parent" to HttpExtraction.safeUrl(target.toString()),"responseStatus" to "${response.code}","port" to "${target.port}","address" to ip))
                    }
                    val extraction = HttpExtraction.analyze(target.toString(), rawHeaders, content, contentType)
                    extraction.references.forEach { ref ->
                        val key = "${ref.kind}:${ref.value}"
                        if(emitted.add(key)) emit(ref.kind, ref.value, "Referência observada, não testada", mapOf("source" to ref.source, "location" to ref.location, "parent" to HttpExtraction.safeUrl(target.toString()), "origin" to when(ref.sameOrigin) { true -> "same-origin"; false -> "external"; null -> "not-inferred" }, "confidence" to "observação textual"))
                    }
                    extraction.indicators.forEach { indicator -> emit("evidence", target.toString(), "Indício ${indicator.rule}; requer contexto", mapOf("rule" to indicator.rule, "source" to indicator.source, "location" to indicator.location, "confidence" to indicator.confidence, "sample" to "[mascarado]", "meaning" to indicator.meaning)) }
                    if(extraction.htmlRedirect) emit("html-redirect", target.toString(), "Refresh HTML observado, não seguido", mapOf("source" to "HTML meta refresh"))
                    HttpPage(target, response.code, response.header("Location"), extraction.scripts)
                }
            } finally { calls.remove(call); metrics.remove(call); watcher.cancel() }
        }
        try {
            var finalPage: HttpPage? = null
            for(hop in 0..8) {
                ensureActive()
                if(!seen.add(current.toString())) { emit("http", current.toString(), "Loop de redirecionamento"); break }
                val page = request(current, hop, false)
                if(page.code !in setOf(301,302,303,307,308) || page.location == null) { finalPage = page; break }
                val next = current.resolve(page.location) ?: throw IOException("Redirect inválido")
                require(next.username.isEmpty() && next.password.isEmpty()) { "Redirect inclui credenciais" }
                emit(HttpExtraction.hostKind(next.host), next.host, "Alvo de redirect HTTP", mapOf("source" to "Location", "parent" to HttpExtraction.safeUrl(current.toString()), "origin" to if(HttpExtraction.sameOrigin(current,next)) "same-origin" else "external"))
                if(current.isHttps && !next.isHttps) { emit("http", current.toString(), "Redirect para HTTP claro registrado e não seguido"); break }
                if(hop == 8) { emit("http", current.toString(), "Limite de 8 redirects atingido"); break }
                current = next
            }
            if(includeScripts && finalPage != null) {
                val parent = finalPage.url
                val scripts = finalPage.scripts.map { it.toHttpUrl() }.filter { HttpExtraction.sameOrigin(parent,it) }.distinct().take(5)
                emit("script-plan", parent.toString(), "${scripts.size} scripts da mesma origem selecionados", mapOf("total" to "${scripts.size}", "limit" to "5 scripts; 64 KiB cada; sem recursão"))
                scripts.forEachIndexed { index, scriptUrl ->
                    ensureActive(); checkOpen()
                    try {
                        val page = request(scriptUrl, 0, true)
                        if(page.code in setOf(301,302,303,307,308)) emit("script", scriptUrl.toString(), "Redirect de script registrado e não seguido", mapOf("parent" to HttpExtraction.safeUrl(parent.toString())))
                    } catch(e:CancellationException) {throw e} catch(e:Exception) {emit("script-error", scriptUrl.toString(), "Falha ao buscar script: ${e.javaClass.simpleName}", mapOf("parent" to HttpExtraction.safeUrl(parent.toString())))}
                    emit("script-progress", parent.toString(), "Scripts processados", mapOf("processed" to "${index+1}", "total" to "${scripts.size}"))
                }
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    suspend fun ssh(host: String, port: Int = 22) {
        emit("stage-progress",host,"Lendo banner SSH na porta $port")
        require(port in 1..65535)
        val address = addresses(host).firstOrNull() ?: error("Nenhum endereço resolvido para o alvo")
        socketWork { socket -> socket.connect(InetSocketAddress(address, port), 3000); socket.soTimeout = 3000
            val bytes = ArrayList<Byte>(); while (bytes.size < 512) { val b = socket.getInputStream().read(); if (b == -1 || b == 10) break; bytes.add(b.toByte()) }
            val banner = bytes.toByteArray().toString(Charsets.UTF_8).filter { !it.isISOControl() }
            val observed = banner.startsWith("SSH-")
            if(observed) SecurityAnalysis.versions(banner,"Banner SSH").forEach {f->emit(f.kind,address.hostAddress.orEmpty(),f.title,f.details+mapOf("port" to "$port","address" to address.hostAddress.orEmpty()))}
            emit("service", address.hostAddress.orEmpty(), if(observed) banner else "Protocolo SSH não observado", mapOf("address" to address.hostAddress.orEmpty(), "hostname" to host, "port" to "$port", "type" to if(observed) "ssh" else "unknown", "protocolObserved" to "$observed", "source" to "Banner recebido", "meaning" to "Produto/versão declarados pelo serviço"))
        }
    }
    suspend fun lookupCves(target:String,cpe:String,sourceId:String,evidence:String) {
        emit("stage-progress",target,"Consultando CVEs na NVD (limite 35 s)")
        require(cpe.matches(Regex("cpe:2[.]3:a:[a-z0-9_]+:[a-z0-9_]+:[0-9.]+:[a-z0-9*]+:[*]:[*]:[*]:[*]:[*]:[*]"))) {"Identificador de versão não suportado"}
        val source=mapOf("cpe" to cpe,"sourceRecordId" to sourceId,"evidence" to evidence,"source" to "NVD","queriedAt" to java.time.Instant.now().toString(),"meaning" to "Consulta externa envia somente CPE. Não confirma vulnerabilidade nem ausência de falhas.")
        emit("cve-check",target,"Consulta de possíveis CVEs iniciada",source)
        val url="https://services.nvd.nist.gov/rest/json/cves/2.0".toHttpUrl().newBuilder().addQueryParameter("cpeName",cpe).addQueryParameter("isVulnerable",null).addQueryParameter("resultsPerPage","50").build()
        val client=OkHttpClient.Builder().socketFactory(connection.network.socketFactory).dns(object:Dns {override fun lookup(hostname:String)=connection.network.getAllByName(hostname).toList()}).connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).followRedirects(false).build()
        val call=client.newCall(Request.Builder().url(url).header("Accept","application/json").build());calls.add(call)
        coroutineScope {
            val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {call.cancel()}}
            try {call.execute().use {response->
                if(!response.isSuccessful) {emit("cve-check",target,"Consulta indisponível: HTTP ${response.code}",source+mapOf("status" to "error"));return@use}
                val bytes=response.body?.byteStream()?.use {it.readBytesBounded(2*1024*1024+1)} ?: byteArrayOf()
                require(bytes.size<=2*1024*1024) {"Resposta NVD excedeu o limite"}
                val page=SecurityAnalysis.cves(bytes.toString(Charsets.UTF_8))
                page.findings.forEach {f->emit(f.kind,target,f.title,f.details+(source-"meaning")+mapOf("reference" to f.details.getValue("reference")))}
                emit("cve-check",target,if(page.total==0) "Nenhuma correspondência encontrada" else "${page.findings.size} possíveis CVEs exibidas de ${page.total} resultados",source+mapOf("status" to "complete","total" to "${page.total}","shown" to "${page.findings.size}","partial" to "${page.total>50}"))
            }} catch(e:CancellationException) {throw e} catch(e:Exception) {emit("cve-check",target,"Consulta não concluída: ${e.javaClass.simpleName}",source+mapOf("status" to "error"))} finally {watcher.cancel();calls.remove(call)}
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit) {val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0) break;out.write(buffer,0,n)}
        return out.toByteArray()
    }
    suspend fun tls(host: String, port: Int = 443) {
        emit("stage-progress",host,"Negociando TLS na porta $port")
        require(port in 1..65535)
        val address = addresses(host).firstOrNull() ?: error("Nenhum endereço resolvido para o alvo")
        socketWork { socket -> socket.connect(InetSocketAddress(address, port), 4000); socket.soTimeout = 4000
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket).use { ssl ->
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                try {ssl.startHandshake()} catch(e:javax.net.ssl.SSLHandshakeException) {
                    if(generateSequence<Throwable>(e) {it.cause}.any {it is java.security.cert.CertificateException}) emit("service",address.hostAddress.orEmpty(),"TLS respondeu, mas o certificado não foi validado",mapOf("address" to address.hostAddress.orEmpty(),"port" to "$port","type" to "tls","protocolObserved" to "true","certificateValid" to "false","source" to "Certificado apresentado no handshake"))
                    throw e
                }
                val certificate = ssl.session.peerCertificates.first() as java.security.cert.X509Certificate
                val details = mapOf("address" to address.hostAddress.orEmpty(), "hostname" to host, "port" to "$port", "type" to "tls", "protocolObserved" to "true", "cipher" to ssl.session.cipherSuite, "subject" to certificate.subjectX500Principal.name, "issuer" to certificate.issuerX500Principal.name, "expires" to certificate.notAfter.toString(), "source" to "Handshake TLS com validação")
                emit("tls", address.hostAddress.orEmpty(), "TLS validado: ${ssl.session.protocol}", details)
                certificate.subjectAlternativeNames.orEmpty().take(64).forEach {name->
                    if(name.firstOrNull()==2) (name.getOrNull(1) as? String)?.takeIf {it.matches(Regex("[A-Za-z0-9*_.-]{1,253}"))}?.let {value->
                        emit("domain",value,"Nome observado no certificado TLS",mapOf("source" to "Certificado TLS validado", "parent" to host,"port" to "$port","meaning" to "Certificado não comprova disponibilidade de todos os nomes"))
                    }
                }
                emit("service", address.hostAddress.orEmpty(), "TLS observado; protocolo de aplicação desconhecido", details)
            }
        }
    }
}
