package dev.nettool
import org.junit.Assert.*
import org.junit.Test
class PrivacyTest {
    @Test fun masksCredentialsAndQuery() {
        val safe=Privacy.mask("https://host/path?key=private&next=hidden\nAuthorization: Bearer secret\nCookie: sid=abc")
        assertFalse(safe.contains("private")); assertFalse(safe.contains("hidden")); assertFalse(safe.contains("Bearer secret")); assertFalse(safe.contains("sid=abc"))
        assertTrue(safe.contains("https://host/path"))
    }
    @Test fun csvEscapesSpreadsheetFormulaAndQuotes() {
        val csv=Investigation(records=listOf(Record(kind="http",target="=1+1",message="a,\"b"))).csv()
        assertTrue(csv.contains("\"'=1+1\"")); assertTrue(csv.contains("a,\"\"b"))
    }
}
