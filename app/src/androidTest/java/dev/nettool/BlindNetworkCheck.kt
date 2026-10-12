package dev.nettool

import android.content.Context
import dev.nettool.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Collections

/** Inputs come only from Android network state and observations, never the comparison reference. */
internal fun runBlindNetworkCheck(context:Context):String = runBlocking {
    val monitor=NetworkMonitor(context);monitor.start()
    val records=Collections.synchronizedList(mutableListOf<Record>())
    var operation=Record(kind="operation",target="Descoberta independente",message="Rede informada pelo Android")
    records.add(operation)
    try {
        val wifi=withTimeout(5000) {monitor.wifi.first {it!=null}!!}
        val range=wifi.addresses.first {it.contains('.')}
        val ports=PortProfiles.topTcp(File(context.filesDir,"nmap-services").readText())
        DiagnosticEngine(wifi) {ev->if(ev.kind!="scan-progress") records.add(Record(time=ev.timeMillis,kind=ev.kind,target=ev.target,message=ev.message,details=ev.details,parentId=operation.id))}.use {engine->
            withTimeout(480000) {
                engine.discover(NetworkMath.batches(range).first())
                val hosts=(records.filter {it.kind=="host"&&it.details["present"]=="true"}.map {it.target}+wifi.gateways+wifi.dnsServers.filter {it.startsWith("10.")||it.startsWith("192.168.")}).distinct().take(16)
                engine.inspectDns(hosts)
                for(host in hosts) {
                    operation=Record(kind="operation",target="Portas $host",message="Alvo observado na descoberta ou configuração da rede");records.add(operation)
                    val open=engine.scanPorts(host,ports).filter {it.status==PortStatus.OPEN}
                    open.take(16).forEach {engine.identifyService(host,it.port)}
                }
            }
        }
        "PASS independent discovery; ${records.count {it.kind=="scan-complete"}} scans; ${records.count {it.kind=="port"&&it.details["status"]=="OPEN"}} open port observations"
    } finally {
        monitor.close()
        val session=EvidenceRetention.compact(Investigation(records=records.toList()))
        SessionStore(context).save(session)
        File(context.filesDir,"blind-validation.json").writeText(session.json().toString())
    }
}
