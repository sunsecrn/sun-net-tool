package dev.nettool

import org.junit.Assert.*
import org.junit.Test

class AnalysisCopyTest {
    @Test fun preservesEvidenceAndSessionLimitsWithoutCredentials() {
        val record=Record(id="evidence-1",kind="http",target="https://example.test/?key=hidden",message="response",details=mapOf("authorization" to "Bearer secret-value","sourceRecordId" to "source-1"))
        val text=AnalysisCopy.session(Investigation(id="session-1",records=listOf(record),dropped=12))
        assertTrue(text.contains("evidence-1"))
        assertTrue(text.contains("source-1"))
        assertTrue(text.contains("anteriormente: 12"))
        assertFalse(text.contains("hidden"))
        assertFalse(text.contains("secret-value"))
        assertTrue(text.contains("não comprovam vulnerabilidade"))
    }
    @Test fun multipartPreservesEveryCharacterAndFits() {
        val value="Rede 🔎\n".repeat(50_000)
        val parts=AnalysisCopy.parts(value)
        assertEquals(value,parts.joinToString(""))
        assertTrue(parts.size>1)
        assertTrue(parts.all {AnalysisCopy.fits(it)})
        assertFalse(parts.any {Character.isHighSurrogate(it.last())})
    }
    @Test fun clipboardLimitCountsUtf8Bytes() {
        assertTrue(AnalysisCopy.fits("a".repeat(AnalysisCopy.MAX_BYTES)))
        assertFalse(AnalysisCopy.fits("á".repeat(AnalysisCopy.MAX_BYTES)))
    }
}
