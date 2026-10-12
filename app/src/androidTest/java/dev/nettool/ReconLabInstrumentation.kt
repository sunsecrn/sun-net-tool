package dev.nettool

import android.app.Instrumentation
import android.os.Bundle
import dev.nettool.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Collections

/** Explicit opt-in hardware check. Only scans the single lab address passed with -e labHost. */
class ReconLabInstrumentation:Instrumentation() {
    private lateinit var arguments:Bundle
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);this.arguments=arguments ?: Bundle();start()}
    override fun onStart() {
        val outcome=Bundle()
        try {
            if(arguments.getString("clipboardOnly")=="true") {
                targetContext.startActivity(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                val stop=File(targetContext.filesDir,"clipboard-stop");stop.delete()
                val directory=File(targetContext.filesDir,"clipboard-review").apply {mkdirs()}
                directory.listFiles()?.forEach {it.delete()}
                Thread.sleep(2000)
                val clipboard=targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                var previous="";var count=0
                val deadline=System.currentTimeMillis()+240000
                File(directory,"ready").writeText("ready")
                while(System.currentTimeMillis()<deadline && !stop.exists()) {
                    val text=clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    if(text!=previous && (text.startsWith("PARTE ") || text.startsWith("Sessão:"))) {
                        File(directory,"clip-${count++}.txt").writeText(text);previous=text
                    }
                    Thread.sleep(500)
                }
                outcome.putString("result","Clipboard snapshots: $count")
                finish(0,outcome);return
            }
            if(arguments.getString("blindNetwork")=="true") {outcome.putString("result",runBlindNetworkCheck(targetContext));finish(0,outcome);return}
            val host=requireNotNull(arguments.getString("labHost")) {"Pass -e labHost with the owned lab IPv4"}
            NetworkMath.ipv4(host)
            val monitor=NetworkMonitor(targetContext)
            monitor.start()
            try {runBlocking {
                val wifi=withTimeout(5000) {monitor.wifi.first {it!=null}!!}
                if(arguments.getString("securityOnly")=="true") {outcome.putString("result",runSecurityLab(targetContext,wifi,host));return@runBlocking}
                if(arguments.getString("captureOnly")=="true") {
                    targetContext.startActivity(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    delay(1000)
                    outcome.putString("result",runCaptureLab(targetContext,wifi,host))
                    return@runBlocking
                }
                val events=Collections.synchronizedList(mutableListOf<DiagnosticEvent>())
                val engine=DiagnosticEngine(wifi,HttpProfile.browser) {events.add(it)}
                try {
                    engine.discover("$host/32")
                    check(events.any {it.kind=="port"&&it.target==host&&it.details["port"]=="80"&&it.details["status"]=="OPEN"}) {"Lab HTTP80 not discovered"}
                    val ports=PortProfiles.topTcp(File(targetContext.filesDir,"nmap-services").readText())
                    check(ports.size==1000)
                    val scanned=engine.scanPorts(host,ports)
                    check(scanned.size==1000) {"Incomplete common scan"}
                    check(scanned.any {it.port==80&&it.status==PortStatus.OPEN})
                    check(scanned.any {it.port==2222&&it.status==PortStatus.OPEN})
                    check(scanned.any {it.port==8443&&it.status==PortStatus.OPEN})
                    engine.identifyService(host,8443)
                    check(events.any {it.kind=="service"&&it.details["port"]=="8443"&&it.details["type"]=="tls"&&it.details["certificateValid"]=="false"}) {"Missing TLS certificate failure classification"}
                    engine.http("http://$host/headers")
                    check(events.any {it.kind=="http"&&it.details["headers"].orEmpty().contains("Mozilla/5.0")}) {"HTTP profile not applied"}
                    engine.checkPortal("http://$host/generate_204")
                    check(events.any {it.kind=="portal"&&it.details["status"]=="302"}) {"Missing synthetic portal signal"}
                    engine.checkPortal("http://$host/online/generate_204")
                    check(events.any {it.kind=="portal"&&it.details["status"]=="204"})
                    engine.inspectDns(listOf(host))
                    check(events.any {it.kind in setOf("dns","dns-error")}) {"Missing DNS attempt"}
                    engine.http("http://$host/",true)
                    engine.http("http://$host/redirect")
                    engine.ssh(host,2222)
                    check(events.any {it.kind=="http"&&it.message=="GET 200"})
                    check(events.any {it.kind=="evidence"}) {"Missing masked synthetic finding"}
                    check(events.any {it.kind=="domain"&&it.target=="api.example.invalid"}) {"Missing extracted domain"}
                    check(events.any {it.kind=="address"&&it.target=="192.0.2.10"}) {"Missing extracted IP"}
                    check(events.any {it.kind=="service"&&it.details["port"]=="2222"})
                    var rejected=false
                    try {engine.http("https://$host:8443/health")} catch(ex:javax.net.ssl.SSLException) {rejected=true}
                    check(rejected) {"Self-signed lab certificate must be rejected"}
                    val session=Investigation(records=events.filter {it.kind!="scan-progress"}.map {Record(time=it.timeMillis,kind=it.kind,target=it.target,message=it.message,details=it.details)})
                    SessionStore(targetContext).save(EvidenceRetention.compact(session))
                    File(targetContext.filesDir,"lab-validation.json").writeText(session.json().toString(2))
                    outcome.putString("result","PASS headers,portal redirect+204,DNS attempts,TLS failure classification,discovery,1000 TCP ports,HTTP,redirects,JS,masked indicators,IP/domain reuse,SSH,self-signed TLS rejection; session=${session.id}")
                } finally {engine.close()}
            }} finally {monitor.close()}
            finish(0,outcome)
        } catch(ex:Throwable) {outcome.putString("error",ex.toString());finish(1,outcome)}
    }
}
