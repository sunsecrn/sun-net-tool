package dev.nettool
import org.junit.Assert.*
import org.junit.Test
class EvidenceRetentionTest {
    @Test fun multipleScansKeepFindingsAndEveryAttempt() {
        val finding=Record(kind="service",target="192.0.2.1",message="HTTP",details=mapOf("port" to "80","protocolObserved" to "true","type" to "http"))
        var s=Investigation(records=listOf(finding))
        for(scan in 1..6) for(port in 1..1000) s=EvidenceRetention.append(s,Record(kind="port",target="192.0.2.1",message="",parentId="op$scan",details=mapOf("port" to "$port","status" to "REFUSED","tcpMs" to "5","cause" to "ECONNREFUSED")))
        assertEquals(0,s.dropped);assertTrue(s.records.any {it.id==finding.id});assertEquals(61,s.records.size)
        assertEquals(6000,s.records.filter {it.kind=="port-batch"}.sumOf {it.details.getValue("count").toInt()})
        assertEquals(1,Inventory.items(s.records).count {it.category=="Serviços"})
    }
    @Test fun resumeIncludesCompactedAttemptsAndSerialization() {
        val op=Record(kind="operation",target="Portas 192.0.2.1",message="",details=mapOf("network" to "wifi"))
        var s=Investigation(records=listOf(op))
        for(port in listOf(80,443)) s=EvidenceRetention.append(s,Record(kind="port",target="192.0.2.1",message="",parentId=op.id,details=mapOf("port" to "$port","status" to "TIMEOUT","tcpMs" to "900")))
        val json=s.json().getJSONArray("records");val restored=(0 until json.length()).map {Record.read(json.getJSONObject(it))}
        assertEquals(listOf(22),Inventory.remaining(restored,"192.0.2.1",listOf(80,443,22)))
    }
}
