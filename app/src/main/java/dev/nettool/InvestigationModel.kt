package dev.nettool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nettool.core.*
import dev.nettool.capture.CaptureState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel

class InvestigationModel(app: Application): AndroidViewModel(app) {
    val monitor = NetworkMonitor(app)
    val httpSettings=HttpSettings(app)
    private val store=SessionStore(app)
    private val journal=DebugJournal.get(app)
    val debugEnabled=journal.enabled
    val debugCount=journal.count
    val debugDropped=journal.dropped
    val debugError=journal.error
    fun trace(action:String, details:Map<String,String> = emptyMap()) = journal.record(action,details + ("sessionId" to _session.value.id))
    fun setDebugEnabled(value:Boolean) { journal.setEnabled(value); if(value) { trace("screen_context"); traceNetwork(monitor.wifi.value) } }
    fun clearDebug() = journal.clear()
    suspend fun debugExport():String = journal.export()
    private fun traceNetwork(wifi:WifiConnection?) = trace("network_changed", if(wifi==null) mapOf("status" to "disconnected") else mapOf("network" to wifi.network.networkHandle.toString(),"interface" to wifi.interfaceName,"addresses" to wifi.addresses.joinToString(),"gateways" to wifi.gateways.joinToString(),"dnsServers" to wifi.dnsServers.joinToString(),"captivePortal" to wifi.captivePortal.toString(),"validated" to wifi.validated.toString()))
    private val saves=Channel<Unit>(Channel.CONFLATED)
    private val pendingSaves=java.util.concurrent.ConcurrentHashMap<String,Investigation>()
    private fun queueSave() { val value=_session.value; pendingSaves[value.id]=value; saves.trySend(Unit) }
    private val _session=MutableStateFlow(Investigation())
    val session=_session.asStateFlow()
    val history=MutableStateFlow<List<Investigation>>(emptyList())
    val busy=MutableStateFlow(false)
    val status=MutableStateFlow("Pronto para investigar")
    val operationStarted=MutableStateFlow(0L)
    val location=SessionLocation(app) {record -> add(record.copy(details=record.details+("captureActive" to "${CaptureState.state.value.running}")))}
    private var lastCaptureRecorded=0L
    private var lastCaptureState:Pair<Boolean,String>?=null
    private var task: Job?=null
    private var engine: DiagnosticEngine?=null
    @Volatile private var sessionLoaded=false
    private fun recordNetwork(wifi:WifiConnection?) {if(wifi!=null) add(Record(kind="network",target="Wi-Fi",message=if(wifi.captivePortal) "Portal indicado pelo Android" else if(wifi.validated) "Internet validada pelo Android" else "Internet ainda não validada",details=mapOf("addresses" to wifi.addresses.joinToString(),"gateways" to wifi.gateways.joinToString(),"dnsServers" to wifi.dnsServers.joinToString(),"routes" to wifi.routes.joinToString(),"domains" to wifi.domains.joinToString(),"captivePortal" to "${wifi.captivePortal}","validated" to "${wifi.validated}")))}
    init {
        monitor.start()
        trace("app_open")
        viewModelScope.launch {monitor.wifi.collect {wifi->traceNetwork(wifi);if(sessionLoaded) recordNetwork(wifi)}}
        viewModelScope.launch {
            CaptureState.state.collect { capture ->
                val nextState=capture.running to capture.status
                if(nextState!=lastCaptureState) {
                    trace("capture_state",mapOf("outcome" to if(capture.running) "running" else "stopped", "packets" to "${capture.packets}","bytes" to "${capture.bytes}","flows" to "${capture.flows.size}","forwardingErrors" to "${capture.forwardingErrors}","unsupportedPackets" to "${capture.unsupportedPackets}"))
                    lastCaptureState=nextState
                }
                if (!capture.running && capture.startedAt > lastCaptureRecorded && capture.sessionId == _session.value.id && capture.pcapPath != null) {
                    lastCaptureRecorded=capture.startedAt
                    val parent=Record(kind="capture-summary",target="VPN local",message=capture.status,details=mapOf("packets" to "${capture.packets}","bytes" to "${capture.bytes}","forwardingErrors" to "${capture.forwardingErrors}","unsupportedPackets" to "${capture.unsupportedPackets}","pcap" to java.io.File(capture.pcapPath).name))
                    add(parent)
                    capture.flows.forEach { flow -> add(Record(kind="flow",target=flow.destination,message="${flow.protocol} :${flow.destinationPort}",details=mapOf("source" to "VPN local", "sentBytes" to "${flow.sentBytes}","receivedBytes" to "${flow.receivedBytes}","uid" to (flow.uid?.toString() ?: "desconhecido")),parentId=parent.id)) }
                }
            }
        }
        val initialSessionId=_session.value.id
        viewModelScope.launch(Dispatchers.IO) { history.value=store.list(); history.value.firstOrNull()?.let { latest -> _session.update { current -> if(current.id==initialSessionId && current.records.all {it.kind=="network"}) current.records.fold(EvidenceRetention.compact(latest)) {acc,r->EvidenceRetention.append(acc,r)} else current } }; sessionLoaded=true; recordNetwork(monitor.wifi.value); for (signal in saves) { pendingSaves.keys.toList().forEach { id -> pendingSaves.remove(id)?.let { store.save(it) } }; history.value=store.list() } }
        viewModelScope.launch { monitor.wifi.map { it?.network }.distinctUntilChanged().drop(1).collect { cancel(); status.value="Conexão alterada. Operação interrompida." } }
    }
    fun add(input:Record) {
        val record=if(input.kind=="location") input else input.copy(details=input.details+location.association())
        trace("evidence",mapOf("kind" to record.kind,"target" to record.target,"operationId" to record.parentId.orEmpty()) + record.details.filterKeys { it in setOf("port","status","tcpMs","dnsMs","tlsMs","httpMs","processed","total","rcode","hop","bodyBytes","truncated","packets","bytes","forwardingErrors","unsupportedPackets") } + (Regex("^GET (\\d{3})$").find(record.message)?.let { mapOf("status" to it.groupValues[1]) } ?: emptyMap()))
        _session.update { s -> EvidenceRetention.append(s,record) }; queueSave() }
    fun newSession() { if(CaptureState.state.value.running) return; cancel(); location.resetSession(); _session.value=Investigation(); trace("session_new"); recordNetwork(monitor.wifi.value); queueSave() }
    fun select(s:Investigation) { if(CaptureState.state.value.running) return; cancel(); location.resetSession(); _session.value=EvidenceRetention.compact(s); trace("session_selected") }
    fun cancel() { if(task?.isActive==true) trace("operation_cancel_requested"); task?.cancel(); engine?.close(); engine=null }
    fun run(label:String, details:Map<String,String> = emptyMap(), action:suspend DiagnosticEngine.()->Unit) {
        if (busy.value || task?.isActive == true) { trace("operation_rejected",mapOf("status" to "busy")); return }
        val wifi=monitor.wifi.value ?: run { status.value="Conecte-se a uma rede Wi-Fi."; trace("operation_rejected",mapOf("status" to "no_wifi")); return }
        val parent=Record(kind="operation",target=label,message="Iniciado pelo usuário", details=details+mapOf("network" to wifi.toString()))
        add(parent)
        val started=android.os.SystemClock.elapsedRealtime()
        trace("operation_started",mapOf("kind" to label.substringBefore(' '),"operationId" to parent.id))
        operationStarted.value=started; busy.value=true; status.value="Iniciando: $label"
        task=viewModelScope.launch {
            val routed=if(CaptureState.state.value.running) dev.nettool.capture.CaptureService.captureNetwork()?.let {wifi.copy(network=it)} else wifi
            if(routed==null) {status.value="Aguarde a captura ficar pronta";busy.value=false;return@launch}
            val e=DiagnosticEngine(routed,httpSettings.headers()) { ev -> when(ev.kind) {
                    "scan-progress" -> status.value="${ev.target}: ${ev.details["processed"]}/${ev.details["total"]} portas, última ${ev.details["port"]}"
                    "stage-progress" -> status.value="${ev.message}: ${ev.target}"
                    else -> {
                        if(ev.kind=="host") status.value="Descoberta: ${ev.details["processed"]}/${ev.details["total"]} IPs, último ${ev.target}"
                        if(ev.kind=="scan-plan") status.value="Testando ${ev.details["total"]} portas de ${ev.target}"
                        add(Record(time=ev.timeMillis,kind=ev.kind,target=ev.target,message=ev.message,details=ev.details,parentId=parent.id))
                    }
                } }
            engine=e
            try { withContext(Dispatchers.IO) { e.action() }; status.value="Concluído: $label"; add(Record(kind="operation-complete",target=label,message="Operação concluída",details=mapOf("durationMs" to "${android.os.SystemClock.elapsedRealtime()-started}"),parentId=parent.id)); trace("operation_finished",mapOf("operationId" to parent.id,"outcome" to "completed","durationMs" to "${android.os.SystemClock.elapsedRealtime()-started}")) }
            catch (ex:CancellationException) { status.value="Cancelado"; trace("operation_finished",mapOf("operationId" to parent.id,"outcome" to "cancelled","durationMs" to "${android.os.SystemClock.elapsedRealtime()-started}")); add(Record(kind="cancelled",target=label,message="Operação interrompida",parentId=parent.id)) }
            catch (ex:Exception) { status.value=ex.message ?: "Falha"; trace("operation_finished",mapOf("operationId" to parent.id,"outcome" to "error","errorType" to ex.javaClass.simpleName,"durationMs" to "${android.os.SystemClock.elapsedRealtime()-started}")); add(Record(kind="error",target=label,message=ex.message ?: ex.javaClass.simpleName,parentId=parent.id)) }
            finally { e.close(); if(engine===e) engine=null; busy.value=false }
        }
    }
    override fun onCleared() { cancel(); location.close(); monitor.close(); saves.close() }
}
