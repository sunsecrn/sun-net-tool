package dev.nettool.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.IDN
import java.net.InetAddress

/** Pure, bounded parsing. References are observations, never a proof of reachability. */
object HttpExtraction {
    const val MAX_REFERENCES = 128
    data class Reference(val kind: String, val value: String, val source: String, val location: String, val sameOrigin: Boolean?)
    data class Indicator(val rule: String, val source: String, val location: String, val confidence: String, val meaning: String)
    data class Result(val references: List<Reference>, val indicators: List<Indicator>, val scripts: List<String>, val htmlRedirect: Boolean)
    private val urlPattern = Regex("https?:(?:\\\\?/){2}[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    private val ipv4Pattern = Regex("(?<![A-Za-z0-9_.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9_.])")
    private val ipv6Pattern = Regex("(?<![A-Za-z0-9_:])(?:[A-Fa-f0-9]{0,4}:){2,}[A-Fa-f0-9:.]{0,39}(?![A-Za-z0-9_:])")
    private val domainPattern = Regex("(?<![A-Za-z0-9_@.-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}(?![A-Za-z0-9_.-])")
    private val fileSuffixes = setOf("js", "css", "json", "html", "htm", "xml", "png", "jpg", "jpeg", "gif", "webp", "svg", "woff", "woff2", "map", "txt", "pdf", "ico")
    private val jsPrefixes = setOf("window", "document", "console", "object", "array", "math", "json", "navigator", "location", "this", "process")
    private val sensitiveName = Regex("(?i)(?:authorization|cookie|password|passwd|secret|token|api[-_]?key|session)")
    private val privateKey = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
    private val assignments = Regex("(?i)([\"']?)(api[_-]?key|secret|access[_-]?token|session[_-]?(?:id|token)?|password)\\1\\s*[:=]\\s*[\"']([^\"'\\r\\n]{12,})[\"']")
    private val unquotedAssignments = Regex("(?i)\\b(?:password|passwd|secret|access[_-]?token|session[_-]?(?:id|token)?)\\s*[:=]\\s*[A-Za-z0-9._~+/-]{8,}")
    private val jwt = Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])")

    fun literalAddress(value: String): InetAddress? {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        return if (host.contains(':')) {
            if (!host.matches(Regex("[0-9A-Fa-f:.]+"))) null else runCatching { InetAddress.getByName(host) }.getOrNull()
        } else {
            runCatching { NetworkMath.ipv4(host); InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray()) }.getOrNull()
        }
    }
    fun hostKind(host: String): String = if (literalAddress(host) != null) "address" else "domain"
    fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean = first.scheme == second.scheme && first.host == second.host && first.port == second.port
    fun safeUrl(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return Redaction.url(Redaction.text(value))
        val builder = url.newBuilder().username("").password("").query(null).fragment(null)
        var hideNext = false
        url.pathSegments.forEachIndexed { index, segment ->
            val hide = hideNext || jwt.containsMatchIn(segment) || segment.length >= 40
            hideNext = sensitiveName.containsMatchIn(segment)
            if (hide) builder.setPathSegment(index, "[mascarado]")
        }
        return builder.build().toString() + if (url.query != null) "?[parâmetros mascarados]" else ""
    }
    fun safeHeader(name: String, value: String): String {
        if (sensitiveName.containsMatchIn(name)) return "[mascarado]"
        if (name.equals("location", true) || name.equals("referer", true)) return safeUrl(value)
        return jwt.replace(urlPattern.replace(Redaction.text(value.take(4096))) { safeUrl(it.value.replace("\\/", "/")) }, "[mascarado]")
    }
    private fun validDomain(raw: String): String? {
        val value = runCatching { IDN.toASCII(raw.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
        if (value.length > 253 || literalAddress(value) != null) return null
        if (value.split('.').any { it.length !in 1..63 || it.startsWith('-') || it.endsWith('-') || !it.matches(Regex("[a-z0-9-]+")) }) return null
        return value
    }
    fun isTextual(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type.isEmpty() || type.startsWith("text/") || type.contains("javascript") || type.contains("json") || type.contains("xml") || type == "application/x-www-form-urlencoded"
    }
    fun analyze(url: String, headers: Map<String, String>, content: String, contentType: String): Result {
        val base = requireNotNull(url.toHttpUrlOrNull())
        val references = linkedMapOf<String, Reference>()
        val indicators = mutableListOf<Indicator>()
        val scripts = linkedSetOf<String>()
        var htmlRedirect = false
        fun add(kind: String, value: String, source: String, location: String, same: Boolean?) {
            if (references.size < MAX_REFERENCES) references.putIfAbsent("$kind:$value", Reference(kind, value, source, location, same))
        }
        fun host(raw: String, source: String, location: String, same: Boolean?) {
            val address = literalAddress(raw)
            if (address != null) add("address", address.hostAddress.orEmpty(), source, location, same)
            else validDomain(raw)?.let { add("domain", it, source, location, same) }
        }
        fun reference(raw: String, source: String, location: String): HttpUrl? {
            if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("data:") || raw.startsWith("javascript:") || raw.startsWith("mailto:")) return null
            val target = base.resolve(raw.replace("\\/", "/")) ?: return null
            val same = sameOrigin(base, target)
            host(target.host, source, location, same)
            add("reference", safeUrl(target.toString()), source, location, same)
            return target
        }
        fun text(raw: String, source: String) {
            // Mask secret assignments before reference extraction: token text is not an endpoint.
            val visible = jwt.replace(unquotedAssignments.replace(assignments.replace(raw) { " ".repeat(it.value.length) }) { " ".repeat(it.value.length) }) { " ".repeat(it.value.length) }
            urlPattern.findAll(visible).take(MAX_REFERENCES).forEach { m -> reference(m.value.trimEnd('.', ',', ';', ')', '}'), source, "offset:${m.range.first}") }
            val residue = urlPattern.replace(visible) { " ".repeat(it.value.length) }
            ipv4Pattern.findAll(residue).take(MAX_REFERENCES).forEach { m -> literalAddress(m.value)?.let { add("address", it.hostAddress.orEmpty(), source, "offset:${m.range.first}", null) } }
            ipv6Pattern.findAll(residue).take(MAX_REFERENCES).forEach { m -> literalAddress(m.value)?.let { add("address", it.hostAddress.orEmpty(), source, "offset:${m.range.first}", null) } }
            domainPattern.findAll(residue).take(MAX_REFERENCES).forEach { m ->
                val value = m.value.lowercase()
                if (value.substringAfterLast('.') !in fileSuffixes && value.substringBefore('.') !in jsPrefixes) host(value, source, "offset:${m.range.first}", null)
            }
            privateKey.findAll(raw).take(5).forEach { indicators.add(Indicator("private-key-marker", source, "offset:${it.range.first}", "média", "Marcador de chave privada; revisar contexto, não validar online")) }
            assignments.findAll(raw).take(10).forEach { match ->
                val field = match.groupValues[2].lowercase()
                val rule = if (field.contains("session") || field.contains("token")) "session-or-token-context" else "possible-configuration-secret"
                indicators.add(Indicator(rule, source, "offset:${match.range.first}", "baixa", "Padrão textual; token de sessão ou chave pública não comprova exposição explorável"))
            }
            unquotedAssignments.findAll(raw).take(5).forEach { indicators.add(Indicator("possible-configuration-secret", source, "offset:${it.range.first}", "baixa", "Campo sensível em texto; valor mascarado, sem validação ou uso")) }
            jwt.findAll(raw).take(5).forEach { indicators.add(Indicator("jwt-shaped-value", source, "offset:${it.range.first}", "baixa", "Formato de token, sem validação ou uso")) }
        }
        var headerBudget = 32768
        headers.entries.take(80).forEach { (name, value) ->
            val bounded = value.take(minOf(4096, headerBudget)); headerBudget -= bounded.length
            if (sensitiveName.containsMatchIn(name)) {
                indicators.add(Indicator("sensitive-header", "header", name.take(100), "contexto", "Cabeçalho sensível presente; valor mascarado, não comprova falha"))
            } else if (headerBudget >= 0 && bounded.isNotEmpty()) {
                if (name.equals("Location", true)) reference(bounded, "header Location", "header:Location")
                text(bounded, "header ${name.take(100)}")
            }
        }
        if (isTextual(contentType)) {
            val body = content.take(65536)
            text(body, if (contentType.contains("javascript", true)) "JavaScript recebido" else "corpo recebido")
            if (contentType.contains("html", true) || (contentType.isBlank() && body.trimStart().startsWith('<'))) {
                val document = Jsoup.parse(body, url)
                document.select("[href],[src],[action]").take(MAX_REFERENCES).forEachIndexed { index, element ->
                    val attribute = if (element.hasAttr("src")) "src" else if (element.hasAttr("href")) "href" else "action"
                    val absolute = element.absUrl(attribute)
                    val target = reference(absolute, "HTML ${element.tagName()}[$attribute]", "element:$index")
                    if (element.tagName() == "script" && attribute == "src" && target != null && sameOrigin(base, target) && target.username.isEmpty() && target.password.isEmpty() && target.queryParameterNames.none { sensitiveName.containsMatchIn(it) }) scripts.add(target.toString())
                }
                document.select("meta[http-equiv=refresh]").take(3).forEach { element ->
                    htmlRedirect = true
                    Regex("(?i)url\\s*=\\s*['\"]?([^'\"]+)").find(element.attr("content"))?.groupValues?.get(1)?.let { reference(it.trim(), "HTML refresh", "meta[http-equiv=refresh]") }
                }
            }
        }
        return Result(references.values.toList(), indicators.distinct().take(40), scripts.take(5), htmlRedirect)
    }
}
