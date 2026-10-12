package dev.nettool

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DebugJournalTest {
    @Test fun debugNeverIncludesInputBodiesHeadersOrCredentials() {
        val fields=DebugPrivacy.fields(mapOf("target" to "https://user:password@example.com/private/token?secret=abc#sensitive","input" to "my password","body" to "private","headers" to "Cookie: secret","errorType" to "IOException"))
        assertEquals(mapOf("target" to "example.com","errorType" to "IOException"),fields)
    }
    @Test fun debugBufferBoundsAndReportsEvictions() {
        val buffer=DebugBuffer(maxEvents=2,maxBytes=1000)
        repeat(4) {buffer.append(JSONObject().put("time",it))}
        assertEquals(2,buffer.count); assertEquals(2L,buffer.dropped)
        assertEquals(2,buffer.json().getJSONArray("events").getJSONObject(0).getInt("time"))
    }
    @Test fun byteLimitUsesUtf8AndRejectsOversizedEvent() {
        val buffer=DebugBuffer(maxEvents=100,maxBytes=50)
        buffer.append(JSONObject().put("value","á".repeat(30)))
        assertEquals(0,buffer.count); assertEquals(1L,buffer.dropped)
    }
    @Test fun retentionAndClear() {
        val buffer=DebugBuffer(); val now=DebugBuffer.RETENTION_MS+100
        val original=DebugBuffer().apply {append(JSONObject().put("time",0));append(JSONObject().put("time",now))}.json()
        buffer.restore(original,now); assertEquals(1,buffer.count); assertEquals(1L,buffer.dropped)
        buffer.clear();assertEquals(0,buffer.count);assertEquals(0L,buffer.dropped)
    }
}
