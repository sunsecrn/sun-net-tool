package dev.nettool.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded advertisements only; service names do not prove an open port. */
class ServiceDiscovery(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    suspend fun discover(connection: WifiConnection, onEvent: (DiagnosticEvent) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            onEvent(DiagnosticEvent("mdns", "Wi-Fi", "DNS-SD isolado por Network requer Android 13 neste MVP")); return
        }
        val active = AtomicBoolean(true)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val resolving = AtomicBoolean(false)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            listOf("_http._tcp.", "_ipp._tcp.", "_ssh._tcp.").forEach { type ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (active.get()) onEvent(DiagnosticEvent("mdns-error", serviceType, "Falha ao iniciar: $errorCode")) }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!active.get() || seen.size >= 40 || !seen.add(serviceInfo.serviceName + type)) return
                        onEvent(DiagnosticEvent("mdns", serviceInfo.serviceName, "Anúncio $type observado", mapOf("source" to "DNS-SD na Network Wi-Fi", "serviceType" to type)))
                        if (!resolving.compareAndSet(false, true)) return
                        serviceInfo.network = connection.network
                        @Suppress("DEPRECATION")
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving.set(false) }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                resolving.set(false)
                                if (!active.get()) return
                                onEvent(DiagnosticEvent("mdns-resolved", info.host?.hostAddress.orEmpty(), "${info.serviceName}: porta anunciada ${info.port}", mapOf("source" to "DNS-SD resposta", "port" to "${info.port}", "meaning" to "Porta anunciada, não testada por connect")))
                            }
                        })
                    }
                }
                listeners.add(listener)
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, connection.network, Executor { it.run() }, listener)
            }
            onEvent(DiagnosticEvent("stage-progress","Wi-Fi","Escutando anúncios HTTP, impressoras e SSH por 8 s"))
            delay(8000)
        } finally { active.set(false); listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } } }
    }
}
