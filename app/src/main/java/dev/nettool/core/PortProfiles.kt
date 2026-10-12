package dev.nettool.core

/** Reads a locally supplied Nmap service database, without redistributing that database. */
object PortProfiles {
    fun topTcp(text: String): List<Int> {
        require(text.length <= 4 * 1024 * 1024) { "Arquivo maior que 4 MiB" }
        val ranked = text.lineSequence().mapNotNull { line ->
            val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (fields.size < 3 || !fields[1].endsWith("/tcp")) return@mapNotNull null
            val port = fields[1].substringBefore('/').toIntOrNull() ?: return@mapNotNull null
            val frequency = fields[2].toDoubleOrNull() ?: return@mapNotNull null
            if (port !in 1..65535 || !frequency.isFinite() || frequency !in 0.0..1.0) null else port to frequency
        }.groupBy({ it.first }, { it.second }).map { (port, frequencies) -> port to frequencies.max() }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        require(ranked.size >= 1000) { "A base precisa conter pelo menos 1.000 portas TCP com frequência" }
        return ranked.take(1000).map { it.first }
    }
    fun allTcp():List<Int> = (1..65535).toList()
    fun custom(text: String): List<Int> {
        require(text.length<=12000) { "Lista de portas muito longa" }
        val result=linkedSetOf<Int>()
        for(part in text.split(',')) {
            val range=part.trim().split('-')
            require(range.size in 1..2) { "Use portas ou intervalos, como 80,443,8000-8100" }
            val first=range[0].toIntOrNull() ?: error("Porta inválida")
            val last=if(range.size==2) range[1].toIntOrNull() ?: error("Porta inválida") else first
            require(first in 1..65535 && last in first..65535) { "Portas devem estar entre 1 e 65535, em ordem crescente" }
            result.addAll(first..last)
        }
        return result.toList()
    }
}
