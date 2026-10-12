package dev.nettool.core
import org.junit.Assert.*
import org.junit.Test

class SecurityAnalysisTest {
    @Test fun versionsRequireExplicitSupportedProductAndVersion() {
        assertEquals("Apache HTTP Server",SecurityAnalysis.versions("Apache/2.4.49 (Unix)","Server").single().details["product"])
        assertTrue(SecurityAnalysis.versions("nginx","Server").isEmpty())
        assertTrue(SecurityAnalysis.versions("nginx/1.23.4","Server").single().details.getValue("cpe").startsWith("cpe:2.3:a:f5:nginx:"))
        assertTrue(SecurityAnalysis.versions("Apache/2.4.49rc1","Server").isEmpty())
        assertTrue(SecurityAnalysis.versions("Unknown/2.4.49","Server").isEmpty())
        assertEquals("cpe:2.3:a:openbsd:openssh:9.3:p1:*:*:*:*:*:*",SecurityAnalysis.versions("SSH-2.0-OpenSSH_9.3p1 Ubuntu","SSH").single().details["cpe"])
    }
    @Test fun missingPoliciesAreContextualAndNeverCves() {
        val plain=SecurityAnalysis.headers("http://example.test",mapOf("Content-Type" to "text/html"),emptyList(),true)
        assertFalse(plain.any {it.details["header"]=="Strict-Transport-Security"})
        assertTrue(plain.any {it.details["header"]=="Content-Security-Policy"})
        assertFalse(plain.any {it.kind=="cve"})
        val json=SecurityAnalysis.headers("http://example.test",mapOf("Content-Type" to "application/json"),emptyList(),true)
        assertEquals(1,json.size)
        val partial=SecurityAnalysis.headers("https://example.test",emptyMap(),emptyList(),false)
        assertEquals(1,partial.size)
    }
    @Test fun recognizesPoliciesWithoutClaimingOverallSecurity() {
        val headers=mapOf("Content-Type" to "text/html","Strict-Transport-Security" to "max-age=31536000","Content-Security-Policy" to "default-src 'self'; frame-ancestors 'none'","X-Content-Type-Options" to "nosniff")
        val result=SecurityAnalysis.headers("https://example.test",headers,listOf("sid=SECRET; Secure; HttpOnly; SameSite=Lax"),true)
        assertEquals(1,result.size)
        assertTrue(result.single().details["meaning"]!!.contains("não significa"))
    }
    @Test fun cookieValuesAreNeverEvidence() {
        val result=SecurityAnalysis.headers("https://example.test",emptyMap(),listOf("session=SECRET_VALUE"),true)
        assertTrue(result.any {it.details["header"]=="Set-Cookie"})
        assertFalse(result.toString().contains("SECRET_VALUE"))
    }
    @Test fun nvdRejectsMalformedAndPreservesPartialAndUnknownSeverity() {
        assertThrows(Exception::class.java) {SecurityAnalysis.cves("{}")}
        val page=SecurityAnalysis.cves("""{"totalResults":90,"vulnerabilities":[{"cve":{"id":"CVE-2024-12345","descriptions":[{"lang":"en","value":"Example conditions"}]}},{"cve":{"id":"CVE-2024-11111","vulnStatus":"Rejected"}}]}""")
        assertEquals(90,page.total);assertEquals(1,page.findings.size)
        assertEquals("Não informado",page.findings.single().details["cvss"])
        assertTrue(page.findings.single().details["meaning"]!!.contains("não confirmação"))
        assertEquals(0,SecurityAnalysis.cves("""{"totalResults":0,"vulnerabilities":[]}""").total)
    }
}
