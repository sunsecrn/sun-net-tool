package dev.nettool.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class HttpExtractionTest {
    @Test fun classifiesNumericHostsAndDeduplicates() {
        val result = HttpExtraction.analyze("http://10.0.0.1/", mapOf("Location" to "http://10.0.0.2/login"), "10.0.0.2 10.0.0.2 999.1.2.3 api.example.com api.example.com", "text/plain")
        assertEquals(1, result.references.count { it.kind == "address" && it.value == "10.0.0.2" })
        assertFalse(result.references.any { it.value == "999.1.2.3" })
        assertFalse(result.references.any { it.kind == "domain" && it.value == "10.0.0.2" })
        assertEquals(1, result.references.count { it.value == "api.example.com" })
    }
    @Test fun comparesSchemeHostAndPortForOrigins() {
        val base = "https://portal.example:443/path".toHttpUrl()
        assertTrue(HttpExtraction.sameOrigin(base, "https://portal.example/a.js".toHttpUrl()))
        assertFalse(HttpExtraction.sameOrigin(base, "http://portal.example/a.js".toHttpUrl()))
        assertFalse(HttpExtraction.sameOrigin(base, "https://portal.example:444/a.js".toHttpUrl()))
        assertFalse(HttpExtraction.sameOrigin(base, "https://cdn.portal.example/a.js".toHttpUrl()))
    }
    @Test fun collectsOnlyFiveEligibleSameOriginScripts() {
        val body = (1..9).joinToString("") { "<script src='/app$it.js'></script>" } + "<script src='https://cdn.example/app.js'></script><script src='/private.js?token=abcdef'></script><script src='//user:pass@portal.example/x.js'></script>"
        val result = HttpExtraction.analyze("https://portal.example/", emptyMap(), body, "text/html")
        assertEquals(5, result.scripts.size)
        assertTrue(result.scripts.all { it.startsWith("https://portal.example/app") })
        assertTrue(result.references.any { it.value.startsWith("https://cdn.example") && it.sameOrigin == false })
    }
    @Test fun masksCredentialsAndSensitiveIndicators() {
        val secret = "VERY_SECRET_VALUE_123456789"
        val body = "const apiKey = '$secret'; const sessionToken = '$secret'; const url='https://user:password@api.example/token/$secret?key=$secret';"
        val result = HttpExtraction.analyze("https://portal.example/", mapOf("Set-Cookie" to "session=$secret", "X-Api-Key" to secret), body, "application/javascript")
        assertTrue(result.indicators.any { it.rule == "possible-configuration-secret" })
        assertTrue(result.indicators.any { it.rule == "session-or-token-context" })
        assertFalse(result.toString().contains(secret))
        assertFalse(result.toString().contains("user:password"))
        assertEquals("[mascarado]", HttpExtraction.safeHeader("X-Api-Key", secret))
    }
    @Test fun doesNotTurnUnquotedSecretsIntoEndpoints() {
        val result = HttpExtraction.analyze("https://portal.example/", emptyMap(), "password: verysecret.example.com; server: real.example.com", "text/plain")
        assertFalse(result.references.any { it.value.contains("verysecret") })
        assertTrue(result.references.any { it.value == "real.example.com" })
        assertTrue(result.indicators.any { it.rule == "possible-configuration-secret" })
    }
    @Test fun recognizesIpv6WithoutResolvingNames() {
        assertEquals("address", HttpExtraction.hostKind("2001:db8::2"))
        assertEquals("domain", HttpExtraction.hostKind("router.local"))
        val result = HttpExtraction.analyze("https://portal.example/", emptyMap(), "See https://[2001:db8::2]/ and 2001:db8::2", "text/plain")
        assertEquals(1, result.references.count { it.kind == "address" })
        assertTrue(result.references.any { it.kind == "reference" && it.value.contains("2001:db8::2") })
    }
    @Test fun skipsBinaryBodiesAndCommonJavascriptExpressions() {
        assertTrue(HttpExtraction.analyze("http://portal.example/", emptyMap(), "10.0.0.1", "image/png").references.isEmpty())
        val result = HttpExtraction.analyze("http://portal.example/", emptyMap(), "window.location; document.cookie; app.js; api.example.com", "application/javascript")
        assertEquals(listOf("api.example.com"), result.references.map { it.value })
    }
    @Test fun resolvesHtmlAndHeaderReferencesWithProvenance() {
        val result = HttpExtraction.analyze("https://portal.example/dir/", mapOf("Link" to "<https://cdn.example/app.js>; rel=preload"), "<meta http-equiv='refresh' content='0;url=/login'><a href='/help'>help</a>", "text/html")
        assertTrue(result.htmlRedirect)
        assertTrue(result.references.any { it.value == "https://portal.example/login" && it.source == "HTML refresh" })
        assertTrue(result.references.any { it.value == "https://cdn.example/app.js" && it.source.startsWith("header") })
        assertTrue(result.references.any { it.value == "https://portal.example/help" && it.sameOrigin == true })
    }
    @Test fun capsReferencesForLargeDocuments() {
        val body = (1..1000).joinToString(" ") { "https://host$it.example/path" }
        assertTrue(HttpExtraction.analyze("https://portal.example/", emptyMap(), body, "text/plain").references.size <= HttpExtraction.MAX_REFERENCES)
    }
}
