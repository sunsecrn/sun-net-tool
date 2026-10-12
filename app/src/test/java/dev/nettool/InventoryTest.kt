package dev.nettool
import dev.nettool.core.PortProfiles
import org.junit.Assert.*
import org.junit.Test
class InventoryTest {
    @Test fun repeatedFindingsShowNewestEvidenceFirst() {
        val old=Record(kind="version",target="http://example.test",message="version",time=1)
        val newer=old.copy(id="new",time=2)
        assertEquals(listOf(newer),Inventory.findings(listOf(old,newer)))
    }

    @Test fun fullProfileAndCustomRangesCoverEndpointsWithoutDuplication() {
        val all=PortProfiles.allTcp()
        assertEquals(65535,all.size);assertEquals(1,all.first());assertEquals(65535,all.last())
        assertEquals(listOf(80,81,82,443),PortProfiles.custom("80-82,81,443"))
        assertThrows(IllegalArgumentException::class.java) {PortProfiles.custom("82-80")}
        assertThrows(IllegalArgumentException::class.java) {PortProfiles.custom("0-65535")}
    }

    @Test fun linksPtrNameWithoutInventingServiceIdentity() {
        val name=Record(kind="domain",target="printer.example",message="PTR",details=mapOf("source" to "DNS PTR","parent" to "192.0.2.5"))
        val port=Record(kind="port",target="192.0.2.5",message="open",details=mapOf("port" to "1234","status" to "OPEN"))
        val service=Inventory.items(listOf(port,name)).single {it.category=="Serviços"}
        assertTrue(name in service.records)
        assertEquals("TCP aberto, tipo não identificado",service.service)
    }

    @Test fun deduplicatesAndRecognizesOnlyObservedProtocols() {
        val records=listOf(
            Record(kind="domain",target="192.168.1.1",message="legacy"),
            Record(kind="address",target="192.168.1.1",message="DNS"),
            Record(kind="port",target="192.168.1.1",message="open",details=mapOf("port" to "80","status" to "OPEN")),
            Record(kind="port",target="192.168.1.1",message="open",details=mapOf("port" to "443","status" to "OPEN")),
            Record(kind="http",target="http://192.168.1.1/",message="GET 200",details=mapOf("remoteIp" to "192.168.1.1")))
        val inventory=Inventory.items(records)
        assertEquals(1,inventory.count {it.category=="IPs"})
        assertEquals(0,inventory.count {it.category=="Domínios"})
        assertEquals("HTTP",inventory.single {it.port==80}.service)
        assertEquals("TCP aberto, tipo não identificado",inventory.single {it.port==443}.service)
    }
    @Test fun ranksDistinctTcpPortsAndRejectsIncompleteDatabase() {
        val source=(1..1001).joinToString("\n") {"service $it/tcp ${it/10000.0}"}+"\nignore 65535/udp 1.0\nrepeat 1001/tcp 0.01"
        val ports=PortProfiles.topTcp(source)
        assertEquals(1000,ports.size);assertEquals(1001,ports.first());assertFalse(1 in ports);assertFalse(65535 in ports)
        assertThrows(IllegalArgumentException::class.java) {PortProfiles.topTcp("http 80/tcp 0.5")}
    }
    @Test fun resumeIsLimitedToLatestOperationAndHost() {
        val op=Record(kind="operation",target="Portas 192.168.1.1",message="")
        val other=Record(kind="port",target="192.168.1.2",message="",parentId=op.id,details=mapOf("port" to "80"))
        val done=other.copy(target="192.168.1.1",details=mapOf("port" to "443"))
        assertEquals(listOf(80),Inventory.remaining(listOf(op,other,done),"192.168.1.1",listOf(80,443)))
    }
    @Test fun resumesDomainScanThroughResolvedIpAndRejectsAnotherNetwork() {
        val first=Record(kind="operation",target="Portas lab.invalid",message="",details=mapOf("network" to "wifi1"))
        val plan=Record(kind="scan-plan",target="192.168.1.1",message="",parentId=first.id)
        val done=Record(kind="port",target="192.168.1.1",message="",parentId=first.id,details=mapOf("port" to "80"))
        val resumed=Record(kind="operation",target="Portas 192.168.1.1",message="",details=mapOf("network" to "wifi1","resumeOf" to first.id))
        val records=listOf(first,plan,done,resumed)
        assertEquals(listOf(443),Inventory.remaining(records,"192.168.1.1",listOf(80,443)))
        assertEquals(listOf(80,443),Inventory.remaining(records.dropLast(1)+resumed.copy(details=mapOf("network" to "wifi2","resumeOf" to first.id)),"192.168.1.1",listOf(80,443)))
    }
}
