package dev.nettool.core

import android.content.Context
import android.net.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WifiConnection(val network: Network, val interfaceName: String, val addresses: List<String>, val gateways: List<String>, val dnsServers: List<String>, val captivePortal: Boolean, val validated: Boolean, val routes:List<String> = emptyList(), val domains:List<String> = emptyList())

class NetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow<WifiConnection?>(null)
    val wifi: StateFlow<WifiConnection?> = mutable
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network)
        override fun onLost(network: Network) { if (mutable.value?.network == network) { mutable.value = null; manager.allNetworks.filter { it != network }.forEach(::update) } }
    }
    @Synchronized private fun update(network: Network) {
        if (mutable.value != null && mutable.value?.network != network) return
        val caps = manager.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
        val link = manager.getLinkProperties(network) ?: return
        mutable.value = WifiConnection(network, link.interfaceName.orEmpty(), link.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" }, link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }, link.dnsServers.mapNotNull { it.hostAddress }, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),link.routes.map {it.destination.toString()},link.domains.orEmpty().split(Regex("[,\\s]+" )).filter {it.isNotBlank()})
    }
    fun start() {
        if (started) return
        started = true
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        manager.allNetworks.forEach(::update)
    }
    override fun close() { if (started) { manager.unregisterNetworkCallback(callback); started = false; mutable.value = null } }
}
