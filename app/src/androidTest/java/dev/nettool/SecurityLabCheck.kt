package dev.nettool
import android.content.Context
import dev.nettool.core.*
import kotlinx.coroutines.*
import java.io.File

internal suspend fun runSecurityLab(context:Context,wifi:WifiConnection,host:String):String {
    val op=Record(kind="operation",target="Laboratório de segurança",message="Fixture declarativa, sem software vulnerável")
    val records=mutableListOf(op)
    val engine=DiagnosticEngine(wifi) {ev->records.add(Record(time=ev.timeMillis,kind=ev.kind,target=ev.target,message=ev.message,details=ev.details,parentId=op.id))}
    try {
        engine.http("http://$host:18080/weak")
        val version=records.first {it.kind=="version"&&it.details["product"]=="Apache HTTP Server"}
        check(version.details["evidence"]=="Apache/2.4.49")
        check(records.any {it.kind=="security-header"&&it.details["header"]=="Content-Security-Policy"})
        check(records.none {it.details["evidence"].orEmpty().contains("LAB_COOKIE_VALUE")})
        engine.http("http://$host:18080/healthy")
        check(records.none {it.target.endsWith("/healthy")&&it.kind=="security-header"&&it.details["assessment"]=="Revisar configuração"})
        engine.lookupCves(version.target,version.details.getValue("cpe"),version.id,version.details.getValue("evidence"))
        check(records.any {it.kind=="cve-check"&&it.details["status"]=="complete"}) {"NVD online lookup did not complete"}
        check(records.any {it.kind=="cve"&&it.details["sourceRecordId"]==version.id}) {"No CVE linked to version evidence"}
        return "PASS version evidence, contextual headers, cookie redaction, healthy fixture, live NVD and source linkage; ${records.count {it.kind=="cve"}} CVEs"
    } finally {
        engine.close();val session=Investigation(records=records);SessionStore(context).save(session)
        File(context.filesDir,"security-validation.json").writeText(session.json().toString())
    }
}
