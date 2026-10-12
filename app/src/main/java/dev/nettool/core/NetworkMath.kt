package dev.nettool.core

object NetworkMath {
    fun ipv4(value: String): Long {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }) { "IPv4 inválido" }
        return parts.fold(0L) { value, part -> (value shl 8) or part.toLong() }
    }
    fun dotted(value: Long) = (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
    fun targets(cidr: String, limit: Int = 256): List<String> {
        require(limit in 1..256)
        val parts = cidr.trim().split('/')
        require(parts.size == 2) { "Informe IPv4/prefixo" }
        val ip = ipv4(parts[0]); val prefix = parts[1].toInt()
        require(prefix in 0..32)
        val size = 1L shl (32 - prefix)
        val base = ip and (0xffffffffL shl (32 - prefix))
        val count = if (prefix < 31) size - 2 else size
        require(count <= limit) { "Faixa contém $count alvos. Escolha até $limit explicitamente." }
        val first = base + if (prefix < 31) 1 else 0
        return (0 until count.toInt()).map { dotted(first + it) }
    }
    fun batches(cidr:String):List<String> {
        val parts=cidr.split('/');require(parts.size==2)
        val prefix=parts[1].toInt();require(prefix in 0..32)
        val ip=ipv4(parts[0]);val base=ip and (0xffffffffL shl (32-prefix))
        if(prefix>=24) return listOf("${dotted(base)}/$prefix")
        return (0 until minOf(16,1 shl (24-prefix))).map {"${dotted(base+it*256L)}/24"}
    }
    fun ports(value: String): List<Int> = value.split(',').map { it.trim().toInt().also { port -> require(port in 1..65535) } }.distinct().also { require(it.size in 1..32) { "Escolha de 1 a 32 portas" } }
}

object Redaction {
    private val secret = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*|((?:password|passwd|secret|token|api[_-]?key|session|authorization)\\s*[=:]\\s*[\"']?)[^\\s\"'&<>;,}]+")
    fun text(value: String): String = secret.replace(value) { match -> (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()) + "[mascarado]" }
    fun header(name: String, value: String): String = if (name.lowercase() in setOf("authorization", "proxy-authorization", "cookie", "set-cookie")) "[mascarado]" else if (name.equals("location", true) || name.equals("referer", true)) url(value) else text(value)
    fun url(value: String): String = value.substringBefore('?').substringBefore('#').replace(Regex("(://)[^/@]+@"), "$1[credenciais mascaradas]@") + if ('?' in value) "?[parâmetros mascarados]" else ""
}
