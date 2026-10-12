package dev.nettool.capture

/** Masks before snippets are sliced, preserving character offsets. Matching values never appear in results. */
object CaptureMasking {
    private val headers = Regex("(?im)^(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:[^\\r\\n]*")
    private val assignments = Regex("(?i)(?:[\"']?(?:password|passwd|pwd|secret|token|access_token|refresh_token|api[_-]?key|session(?:id)?|authorization)[\"']?\\s*[=:]\\s*)(?:[\"'][^\"'\\r\\n]*[\"']|[^\\s&;,}<>]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val longValue = Regex("[A-Za-z0-9_+/=-]{24,}")
    private val query = Regex("\\?[^\\s\"'<>#]+")
    private val credentials = Regex("://[^/@\\s]+@")
    private fun maskRange(chars: CharArray, start: Int, end: Int) {
        for (i in start until end.coerceAtMost(chars.size)) if (chars[i] != '\r' && chars[i] != '\n') chars[i] = '*'
    }
    fun text(raw: String): String {
        val output = raw.toCharArray()
        listOf(headers, assignments, bearer, longValue, query, credentials).forEach { regex ->
            regex.findAll(raw).forEach { maskRange(output, it.range.first, it.range.last + 1) }
        }
        return String(output).replace('\u2013', '-').replace('\u2014', '-')
    }
    fun url(raw: String): String = text(raw.substringBefore('?').substringBefore('#')) + if ('?' in raw) "?[mascarado]" else ""
    fun snippet(raw: String, start: Int, end: Int): String {
        val masked = text(raw).toCharArray()
        maskRange(masked, start, maxOf(start + 1, end))
        val left = (start - 70).coerceAtLeast(0)
        val right = (maxOf(end, start + 1) + 70).coerceAtMost(masked.size)
        return String(masked, left, right - left).replace('\r', ' ').replace('\n', ' ')
            .map { if (it.isISOControl()) ' ' else it }.joinToString("").take(280)
    }
}
