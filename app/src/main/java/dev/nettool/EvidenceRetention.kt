package dev.nettool

/** Negative attempts are grouped in small batches without losing ports, timings or causes. */
object EvidenceRetention {
    private fun negative(r:Record)=r.kind=="port" && r.details["status"] in setOf("REFUSED","TIMEOUT","ERROR")
    fun append(s:Investigation,r:Record):Investigation {
        val list=s.records.toMutableList()
        if(negative(r)) {
            val index=list.indexOfLast {it.kind=="port-batch" && it.parentId==r.parentId && it.target==r.target && it.details["status"]==r.details["status"] && it.details["cause"]==r.details["cause"] && (it.details["count"]?.toIntOrNull() ?: 0)<100}
            val previous=list.getOrNull(index)
            val attempts=listOfNotNull(previous?.details?.get("attempts"),"${r.details["port"]}:${r.details["tcpMs"] ?: "0"}").joinToString(",")
            val count=(previous?.details?.get("count")?.toIntOrNull() ?: 0)+1
            val batch=Record(id=previous?.id ?: r.id,time=r.time,kind="port-batch",target=r.target,message="$count tentativas: ${when(r.details["status"]) {"REFUSED"->"conexão recusada";"TIMEOUT"->"sem resposta no prazo";else->"erro de conexão"}}",parentId=r.parentId,details=r.details.minus("port").minus("tcpMs")+mapOf("attempts" to attempts,"count" to "$count","format" to "porta:milissegundos"))
            if(index>=0) list[index]=batch else list.add(batch)
        } else list.add(r)
        var dropped=s.dropped
        while(list.size>4000) {
            val low=list.indexOfFirst {it.kind=="port-batch" || it.kind=="host" && it.details["present"]=="false"}
            list.removeAt(if(low>=0) low else 0);dropped++
        }
        return s.copy(records=list,dropped=dropped)
    }
    fun compact(s:Investigation)=s.records.fold(s.copy(records=emptyList())) {acc,r->append(acc,r)}
}
