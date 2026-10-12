package dev.nettool

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.ArrayDeque

/** Explicit allowlist: debug never stores text input, HTTP bodies, headers or packet payload. */
object DebugPrivacy {
    private val keys = setOf("screen", "type", "accepted", "expanded", "action", "format", "outcome", "errorType", "sessionId", "operationId", "kind", "target", "network", "interface", "addresses", "gateways", "dnsServers", "captivePortal", "validated", "durationMs", "port", "status", "tcpMs", "dnsMs", "tlsMs", "httpMs", "processed", "total", "rcode", "hop", "bodyBytes", "truncated", "packets", "bytes", "flows", "forwardingErrors", "unsupportedPackets", "enabled", "sdk", "model", "version")
    fun target(value: String): String {
        if (value.contains("://")) return runCatching { URI(value).host.orEmpty() }.getOrDefault("")
        return value.takeIf { it.length <= 253 && it.matches(Regex("[A-Za-z0-9.:%_/-]+")) && !it.contains('@') }.orEmpty()
    }
    fun fields(values: Map<String, String>): Map<String, String> = values.filterKeys { it in keys }.mapValues { (key,value) ->
        if(key=="target") target(value) else Privacy.mask(value).replace(Regex("[\\r\\n\\t]")," ").take(500)
    }
}

/** Pure bounded buffer, also used by fixture tests. */
class DebugBuffer(private val maxEvents: Int = 2000, private val maxBytes: Int = 1024 * 1024 - 8192) {
    private val events=ArrayDeque<String>()
    private var sizeBytes=0
    var dropped=0L
        private set
    val count: Int get()=events.size
    fun append(event:JSONObject) {
        val text=event.toString(); val bytes=text.toByteArray(Charsets.UTF_8).size
        if(bytes>maxBytes) { dropped++; return }
        events.addLast(text); sizeBytes+=bytes
        while(events.size>maxEvents || sizeBytes>maxBytes) { sizeBytes-=events.removeFirst().toByteArray(Charsets.UTF_8).size; dropped++ }
    }
    fun restore(root:JSONObject, now:Long) {
        dropped=root.optLong("dropped")
        val array=root.optJSONArray("events") ?: return
        for(i in 0 until array.length()) {
            val e=array.getJSONObject(i)
            if(now-e.optLong("time") <= RETENTION_MS) append(e) else dropped++
        }
    }
    fun prune(now:Long) {
        while(events.isNotEmpty() && now-JSONObject(events.first).optLong("time")>RETENTION_MS) {
            sizeBytes-=events.removeFirst().toByteArray(Charsets.UTF_8).size; dropped++
        }
    }
    fun json(queueDropped:Long=0) = JSONObject().put("schema",1).put("source","sun-nettool-opt-in-debug").put("dropped",dropped+queueDropped).put("limits",JSONObject().put("events",maxEvents).put("bytes",maxBytes).put("retentionDays",7)).put("events",JSONArray(events.map { JSONObject(it) }))
    fun clear() { events.clear(); sizeBytes=0; dropped=0 }
    companion object { const val RETENTION_MS=7*24*60*60*1000L }
}

class DebugJournal private constructor(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("debug-consent",Context.MODE_PRIVATE)
    private val active=MutableStateFlow(prefs.getBoolean("enabled",false))
    val enabled=active.asStateFlow()
    private val counts=MutableStateFlow(0)
    val count=counts.asStateFlow()
    private val drops=MutableStateFlow(0)
    val dropped=drops.asStateFlow()
    private val errors=MutableStateFlow<String?>(null)
    val error=errors.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val commands=Channel<Command>(256)
    private val queueDropped=java.util.concurrent.atomic.AtomicLong()
    private val file=File(app.filesDir,"debug/journal.json")
    private val buffer=DebugBuffer()
    private sealed interface Command {
        data class Event(val json:JSONObject):Command
        data class Export(val reply:CompletableDeferred<String>):Command
        data object Clear:Command
    }
    init {
        scope.launch {
            if(file.isFile) runCatching { buffer.restore(JSONObject(file.readText()),System.currentTimeMillis()) }.onFailure { errors.value="Registro anterior não pôde ser lido" }
            updateCounts()
            for(first in commands) {
                val batch=ArrayList<Command>(64).apply { add(first) }
                while(batch.size<64) { val next=commands.tryReceive().getOrNull() ?: break; batch.add(next) }
                var dirty=false
                for(command in batch) {
                    try {
                        when(command) {
                            is Command.Event -> { buffer.prune(System.currentTimeMillis()); buffer.append(command.json); dirty=true }
                            is Command.Clear -> { buffer.clear(); queueDropped.set(0); dirty=true }
                            is Command.Export -> { buffer.prune(System.currentTimeMillis()); persist(); dirty=false; command.reply.complete(exportObject().toString()) }
                        }
                        errors.value=null
                    } catch(e:Exception) {
                        errors.value="Falha no registro debug: ${e.javaClass.simpleName}"
                        if(command is Command.Export) command.reply.completeExceptionally(e)
                    }
                }
                if(dirty) runCatching { persist() }.onFailure { errors.value="Falha no registro debug: ${it.javaClass.simpleName}" }
                updateCounts()
            }
        }
    }
    private fun exportObject()=buffer.json(queueDropped.get()).put("enabled",active.value).put("exportedAt",System.currentTimeMillis())
    private fun persist() {
        file.parentFile!!.mkdirs()
        val tmp=File(file.parentFile,"journal.tmp")
        tmp.writeText(buffer.json(queueDropped.get()).toString())
        check(tmp.renameTo(file))
    }
    private fun updateCounts() { counts.value=buffer.count; drops.value=(buffer.dropped+queueDropped.get()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    @Synchronized fun setEnabled(value:Boolean) {
        if(active.value==value) return
        if(!value) enqueue("debug_disabled",mapOf("enabled" to "false"))
        active.value=value; prefs.edit().putBoolean("enabled",value).apply()
        if(value) enqueue("debug_enabled",mapOf("enabled" to "true","sdk" to Build.VERSION.SDK_INT.toString(),"model" to Build.MODEL,"version" to BuildConfig.VERSION_NAME))
    }
    @Synchronized fun record(action:String,fields:Map<String,String> = emptyMap()) { if(active.value) enqueue(action,fields) }
    private fun enqueue(action:String,fields:Map<String,String>) {
        // Actions are developer-defined event names, never field contents.
        val name=action.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,60}")) } ?: "event"
        val entry=JSONObject().put("time",System.currentTimeMillis()).put("action",name).put("details",JSONObject(DebugPrivacy.fields(fields)))
        if(!commands.trySend(Command.Event(entry)).isSuccess) { queueDropped.incrementAndGet(); drops.value++ }
    }
    fun clear() { scope.launch { commands.send(Command.Clear) } }
    suspend fun export():String { val reply=CompletableDeferred<String>(); commands.send(Command.Export(reply)); return reply.await() }
    companion object {
        @Volatile private var instance:DebugJournal?=null
        fun get(context:Context)=instance ?: synchronized(this) { instance ?: DebugJournal(context).also { instance=it } }
    }
}
