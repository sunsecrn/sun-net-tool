package dev.nettool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Reports intentionally omit raw bodies, URL query values and credential headers. */
object Privacy {
    private val credentials = Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*[^\\r\\n,;]+")
    private val query = Regex("([?&][^=&#\\s]+)=([^&#\\s]*)")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    fun mask(value: String): String = jwt.replace(query.replace(credentials.replace(value) { "${it.groupValues[1]}=[mascarado]" }) { "${it.groupValues[1]}=[mascarado]" }, "[token mascarado]").take(12000)
}
data class Record(val id: String = UUID.randomUUID().toString(), val time: Long = System.currentTimeMillis(), val kind: String, val target: String, val message: String, val details: Map<String,String> = emptyMap(), val parentId: String? = null) {
    fun json() = JSONObject().put("id",id).put("time",time).put("kind",kind).put("target",Privacy.mask(target)).put("message",Privacy.mask(message)).put("details",JSONObject(details.mapValues { (key,value) -> if (Regex("(?i)cookie|authorization|secret|password|token").containsMatchIn(key)) "[mascarado]" else Privacy.mask(value) })).put("parentId",parentId)
    companion object { fun read(j: JSONObject) = Record(j.getString("id"), j.getLong("time"), j.getString("kind"),j.getString("target"),j.getString("message"), j.optJSONObject("details")?.let { d -> d.keys().asSequence().associateWith { d.getString(it) } } ?: emptyMap(), j.optString("parentId").takeIf { it.isNotEmpty() }) }
}
data class Investigation(val id: String = UUID.randomUUID().toString(), val created: Long = System.currentTimeMillis(), val records: List<Record> = emptyList(), val dropped: Int = 0) {
    fun json() = JSONObject().put("schema",1).put("id",id).put("created",created).put("dropped",dropped).put("records",JSONArray(records.map { it.json() }))
    fun csv(): String {
        fun q(s:String) = "\"" + (if (s.firstOrNull() in listOf('=','+','-','@')) "'" else "") + Privacy.mask(s).replace("\"","\"\"") + "\""
        return "id,time,kind,target,message,parentId,details\n" + records.joinToString("\n") { r -> listOf(r.id,r.time.toString(),r.kind,r.target,r.message,r.parentId.orEmpty(),r.details.toString()).joinToString(",", transform=::q) }
    }
}
class SessionStore(context: Context) {
    private val dir = File(context.filesDir,"sessions").apply { mkdirs() }
    @Synchronized fun list(): List<Investigation> = dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.mapNotNull { file -> runCatching { val j=JSONObject(file.readText()); val a=j.getJSONArray("records"); Investigation(j.getString("id"),j.getLong("created"),(0 until a.length()).map { Record.read(a.getJSONObject(it)) },j.optInt("dropped")) }.getOrNull() } ?: emptyList()
    @Synchronized fun save(session: Investigation) {
        val tmp=File(dir,"${session.id}.tmp"); tmp.writeText(session.json().toString()); check(tmp.renameTo(File(dir,"${session.id}.json")))
        dir.listFiles()?.filter { it.extension=="json" }?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
    }
}
