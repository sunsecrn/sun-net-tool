package dev.nettool.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject

/** A local TUN VPN backed by zdtun TCP/UDP forwarding. No TLS interception. */
class CaptureService : VpnService() {
    companion object {
        const val ACTION_STOP = "dev.nettool.capture.STOP"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_NETWORK_HANDLE = "networkHandle"
        const val MAX_DURATION_MS = 10 * 60 * 1000L
        private val engineLock = Any()
        private var engineActive = false
        @Volatile private var activeService: CaptureService? = null
        fun physicalDnsServers(): List<InetAddress> = activeService?.let { service ->
            service.selectedNetwork?.let { service.connectivity.getLinkProperties(it)?.dnsServers }
        }.orEmpty()
        fun captureNetwork(): Network? = activeService?.let { service ->
            service.connectivity.allNetworks.firstOrNull { network ->
                service.connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    service.connectivity.getLinkProperties(network)?.linkAddresses?.any {
                        it.address.hostAddress == "10.111.222.1"
                    } == true
            }
        }
        init { System.loadLibrary("nettool_capture") }
    }

    private external fun nativePrepare()
    private external fun nativeRun(fd: Int, networkHandle: Long): Int
    private external fun nativeStop()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectivity: ConnectivityManager
    private var selectedNetwork: Network? = null
    private var callbackRegistered = false
    @Volatile private var ownsEngine = false
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var stopReason = "Captura concluída"
    private var pcap: PcapWriter? = null
    private var pcapFile: File? = null
    private var startedAt = 0L
    private var sessionId = ""
    private var packetCount = 0L
    private var byteCount = 0L
    private var errors = 0L
    private var unsupported = 0L
    private var omitted = 0L
    private var lastPublish = 0L
    private val flows = LinkedHashMap<String, CapturedFlow>()
    private val timeout = Runnable { requestStop("Limite de 10 minutos atingido") }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selectedNetwork) requestStop("Wi-Fi desconectado; captura encerrada")
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("capture", "Captura de rede", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop("Captura encerrada pelo usuário")
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        val stop = PendingIntent.getService(this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, Notification.Builder(this, "capture")
            .setContentTitle("Sun NetTool: captura ativa")
            .setContentText("TCP/UDP do aparelho via Wi-Fi. Toque em Parar para encerrar.")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build()).build())
        try {
            synchronized(engineLock) {
                check(!engineActive) { "Aguarde o encerramento da captura anterior" }
                engineActive = true
                ownsEngine = true
            }
            check(prepare(this) == null) { "Autorize a VPN antes de iniciar" }
            val requestedHandle = intent?.getLongExtra(EXTRA_NETWORK_HANDLE, 0L) ?: 0L
            val network = connectivity.allNetworks.firstOrNull { candidate ->
                val caps = connectivity.getNetworkCapabilities(candidate)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    (requestedHandle == 0L || candidate.networkHandle == requestedHandle)
            } ?: error("Conecte-se a uma rede Wi-Fi física")
            nativePrepare()
            selectedNetwork = network
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            callbackRegistered = true
            val builder = Builder().setSession("NetTool local capture").setMtu(1500)
                .addAddress("10.111.222.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd71:6e65:7474::1", 128).addRoute("::", 0)
                .setUnderlyingNetworks(arrayOf(network)).setBlocking(false)
                .addAllowedApplication(packageName)
            val dns = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
            dns.forEach { builder.addDnsServer(it) }
            val descriptor = builder.establish() ?: error("Consentimento VPN revogado")
            tunnel = descriptor
            activeService = this
            // Validate again after establishing. The chosen physical network must still exist.
            check(connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                descriptor.close(); "Wi-Fi desconectado"
            }
            val dir = File(filesDir, "captures").apply { mkdirs() }
            // Retain at most three capture groups and remove files older than 24 hours.
            dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
            dir.listFiles()?.filter { it.extension == "pcap" }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { old ->
                listOf("pcap", "json", "csv").forEach { File(dir, "${old.nameWithoutExtension}.$it").delete() }
            }
            sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.take(100).orEmpty()
            startedAt = System.currentTimeMillis()
            pcapFile = File(dir, "capture-$startedAt.pcap")
            pcap = PcapWriter(pcapFile!!)
            publish(true, "Capturando TCP/UDP via Wi-Fi")
            worker = Thread({
                try {
                    val result = nativeRun(descriptor.fd, network.networkHandle)
                    if (result != 0) stopReason = "Motor de captura encerrou com código $result"
                } catch (e: Exception) {
                    stopReason = "Falha de captura: ${e.javaClass.simpleName}"
                } finally {
                    runCatching { descriptor.close() }
                    runCatching { pcap?.close() }
                    val exported = runCatching { exportMetadata(dir) }.getOrNull()
                    publish(false, stopReason, exported)
                    synchronized(engineLock) { engineActive = false; ownsEngine = false }
                    handler.post { stopSelf() }
                }
            }, "NetTool capture").also { it.start() }
            handler.postDelayed(timeout, MAX_DURATION_MS)
        } catch (e: Exception) {
            runCatching { tunnel?.close() }
            runCatching { pcap?.close() }
            synchronized(engineLock) {
                if (ownsEngine) { engineActive = false; ownsEngine = false }
            }
            CaptureState.mutable.value = CaptureSnapshot(status = e.message ?: "Falha ao iniciar VPN")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Invoked synchronously by JNI before a socket is connected. */
    fun protectSocket(fd: Int): Boolean = protect(fd)

    /** Invoked on the sole capture worker. Memory never grows beyond 512 flow records. */
    fun recordPacket(bytes: ByteArray, outbound: Boolean) {
        val now = System.currentTimeMillis()
        packetCount++
        byteCount += bytes.size
        if (pcap?.write(bytes, now) == false) {
            requestStop("Limite PCAP de 25 MiB atingido")
            return
        }
        PacketMetadata.parse(bytes)?.let { packet ->
            val source = if (outbound) packet.source else packet.destination
            val destination = if (outbound) packet.destination else packet.source
            val sourcePort = if (outbound) packet.sourcePort else packet.destinationPort
            val destinationPort = if (outbound) packet.destinationPort else packet.sourcePort
            val key = "${packet.protocol}|$source|$sourcePort|$destination|$destinationPort"
            val old = flows[key]
            if (old == null && flows.size >= 512) omitted++
            else {
                val uid = old?.uid ?: if (old == null) runCatching {
                    connectivity.getConnectionOwnerUid(packet.protocol,
                        InetSocketAddress(InetAddress.getByName(source), sourcePort),
                        InetSocketAddress(InetAddress.getByName(destination), destinationPort)).takeIf { it >= 0 }
                }.getOrNull() else null
                val base = old ?: CapturedFlow(source, sourcePort, destination, destinationPort,
                    if (packet.protocol == 6) "TCP" else "UDP", now, now, uid = uid,
                    packages = uid?.let { packageManager.getPackagesForUid(it)?.toList() }.orEmpty())
                flows[key] = base.copy(lastSeen = now, packets = base.packets + 1,
                    sentBytes = base.sentBytes + if (outbound) bytes.size else 0,
                    receivedBytes = base.receivedBytes + if (outbound) 0 else bytes.size)
            }
        }
        if (SystemClock.elapsedRealtime() - lastPublish > 500) publish(true, "Capturando TCP/UDP via Wi-Fi")
    }

    fun recordLoss() { errors++ }
    fun recordUnsupported() { unsupported++ }
    fun publishTick() {
        if (SystemClock.elapsedRealtime() - lastPublish >= 500) publish(true, "Capturando TCP/UDP via Wi-Fi")
    }

    private fun publish(running: Boolean, status: String, exported: Pair<File, File>? = null) {
        lastPublish = SystemClock.elapsedRealtime()
        CaptureState.mutable.value = CaptureSnapshot(running, status, sessionId, startedAt,
            packetCount, byteCount, errors, omitted, flows.values.toList(), pcapFile?.absolutePath,
            exported?.first?.absolutePath, exported?.second?.absolutePath, unsupported)
    }

    private fun exportMetadata(dir: File): Pair<File, File> {
        val base = pcapFile!!.nameWithoutExtension
        val json = File(dir, "$base.json")
        val csv = File(dir, "$base.csv")
        val rows = JSONArray()
        flows.values.forEach { f -> rows.put(JSONObject().put("source", f.source).put("sourcePort", f.sourcePort)
            .put("destination", f.destination).put("destinationPort", f.destinationPort).put("protocol", f.protocol)
            .put("firstSeen", f.firstSeen).put("lastSeen", f.lastSeen).put("sentBytes", f.sentBytes)
            .put("receivedBytes", f.receivedBytes).put("uid", f.uid ?: JSONObject.NULL).put("packages", JSONArray(f.packages))) }
        json.writeText(JSONObject().put("sessionId", sessionId).put("source", "local-vpn-zdtun")
            .put("startedAt", startedAt).put("packets", packetCount).put("forwardingErrors", errors)
            .put("unsupportedPackets", unsupported)
            .put("omittedFlowPackets", omitted).put("flows", rows).toString(2))
        csv.bufferedWriter().use { out ->
            out.appendLine("protocol,source,sourcePort,destination,destinationPort,firstSeen,lastSeen,sentBytes,receivedBytes,uid")
            flows.values.forEach { f -> out.appendLine("${f.protocol},${f.source},${f.sourcePort},${f.destination},${f.destinationPort},${f.firstSeen},${f.lastSeen},${f.sentBytes},${f.receivedBytes},${f.uid ?: ""}") }
        }
        return json to csv
    }

    private fun requestStop(reason: String) { stopReason = reason; if (ownsEngine) nativeStop(); if (worker == null) stopSelf() }
    override fun onRevoke() { requestStop("VPN revogada ou substituída por outra VPN"); super.onRevoke() }
    override fun onDestroy() {
        if (activeService === this) activeService = null
        if (ownsEngine) nativeStop()
        handler.removeCallbacks(timeout)
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
