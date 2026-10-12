package dev.nettool

import java.net.URI

/** Derived evidence views also work on sessions exported by earlier app versions. */
data class InventoryItem(val target: String, val category: String, val port: Int? = null, val service: String? = null, val records: List<Record>) {
    val key get() = "$category|$target|$port"
    val present get() = records.any { it.kind == "http" || it.kind == "service" || it.kind == "tls" || it.details["present"] == "true" || it.details["status"] in setOf("OPEN", "REFUSED") }
}
object Inventory {
    fun isIp(value: String): Boolean = dev.nettool.core.HttpExtraction.literalAddress(value)!=null
    fun items(records: List<Record>): List<InventoryItem> {
        val groups = linkedMapOf<String, MutableList<Record>>()
        val services = linkedMapOf<Pair<String, Int>, MutableList<Record>>()
        fun add(target: String, r: Record) {
            if (target.isNotBlank() && target.matches(Regex("[A-Za-z0-9_.:%-]+"))) groups.getOrPut(target.lowercase()) { mutableListOf() }.add(r)
        }
        for (r in records) {
            if (r.kind in setOf("address", "domain", "service", "tls", "mdns-resolved") || r.kind=="host"&&r.details["present"]=="true" || r.kind in setOf("port","port-batch")&&r.details["status"] in setOf("OPEN","REFUSED")) add(r.target, r)
            if (r.kind == "http" && r.message.startsWith("GET ")) {
                val uri = runCatching { URI(r.target.substringBefore('?').substringBefore('#')) }.getOrNull() ?: continue
                val host = r.details["remoteIp"]?.takeIf { it.isNotBlank() } ?: uri.host ?: continue
                add(host, r)
                val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
                services.getOrPut(host to port) { mutableListOf() }.add(r)
            }
            val port = r.details["port"]?.toIntOrNull()
            if (port != null && (r.kind == "port" && r.details["status"] == "OPEN" || r.kind in setOf("service", "tls", "version", "security-header"))) {
                val host = r.details["address"]?.takeIf { it.isNotBlank() } ?: r.target
                add(host, r)
                services.getOrPut(host to port) { mutableListOf() }.add(r)
            }
        }
        val names=records.filter {it.kind=="domain" && it.details["source"]=="DNS PTR"}
        names.forEach {r->r.details["parent"]?.let {ip->groups[ip]?.add(r)}}
        return groups.map { (target, evidence) -> InventoryItem(target, if (isIp(target)) "IPs" else "Domínios", records = evidence.distinctBy { it.id }) }
            .sortedWith(compareByDescending<InventoryItem> { it.present }.thenBy { it.target }) + services.map { (endpoint, evidence) ->
                val recognized = evidence.lastOrNull { it.details["protocolObserved"] == "true" }?.details?.get("type")
                    ?: evidence.lastOrNull { it.kind == "http" }?.let { if (it.target.startsWith("https:")) "HTTPS" else "HTTP" }
                    ?: evidence.lastOrNull { it.kind == "tls" }?.let { "TLS" }
                    ?: evidence.lastOrNull { it.kind == "service" && it.message.startsWith("SSH-") }?.let { "SSH" }
                InventoryItem(endpoint.first, "Serviços", endpoint.second, recognized?.uppercase() ?: "TCP aberto, tipo não identificado", (evidence+names.filter {it.details["parent"]==endpoint.first}).distinctBy { it.id })
            }
    }
    fun findings(records: List<Record>): List<Record> = records.filter { it.kind in setOf("version", "security-header", "cve", "cve-check", "http", "reference", "evidence", "html-redirect", "tls", "service", "probe", "pcap-match", "capture-search", "dns", "dns-error", "error", "scan-complete", "portal", "network") }
        .asReversed().distinctBy { listOf(it.kind, it.target, it.message, it.details.toString()) }
    fun latestScan(records:List<Record>,host:String):Record? = records.lastOrNull { op ->
        op.kind=="operation" && (op.target=="Portas $host" || records.any {it.kind=="scan-plan" && it.target==host && it.parentId==op.id})
    }
    fun remaining(records: List<Record>, host: String, ports: List<Int>): List<Int> {
        val last = latestScan(records,host) ?: return ports
        val operationIds=mutableSetOf(last.id)
        var previous=last.details["resumeOf"]
        while(previous!=null && previous !in operationIds) {
            val prior=records.firstOrNull {it.id==previous && it.kind=="operation" && it.details["network"]==last.details["network"]} ?: break
            val matches=prior.target=="Portas $host" || records.any {it.kind=="scan-plan"&&it.target==host&&it.parentId==prior.id}
            if(!matches) break
            operationIds.add(prior.id)
            previous=prior.details["resumeOf"]
        }
        val completed = records.filter { it.parentId in operationIds && it.kind in setOf("port","port-batch") && it.target == host }.flatMap {r->if(r.kind=="port-batch") r.details["attempts"].orEmpty().split(",").mapNotNull {it.substringBefore(':').toIntOrNull()} else listOfNotNull(r.details["port"]?.toIntOrNull())}.toSet()
        return ports.filterNot { it in completed }
    }
}
