package dev.nettool

import org.junit.Assert.*
import org.junit.Test

class GeoFixTest {
    private val fix=GeoFix(0.0,0.0,10f,1_000L,100_000_000_000L,"gps",false)
    @Test fun requiresFreshMonotonicFix() {
        assertTrue(fix.usable(160_000_000_000L))
        assertFalse(fix.usable(160_000_000_001L))
        assertFalse(fix.usable(99_000_000_000L))
    }
    @Test fun rejectsInvalidCoordinatesAndAccuracy() {
        assertFalse(fix.copy(latitude=91.0).usable(fix.elapsedNanos))
        assertFalse(fix.copy(longitude=Double.NaN).usable(fix.elapsedNanos))
        assertFalse(fix.copy(accuracy=-1f).usable(fix.elapsedNanos))
        assertFalse(fix.copy(accuracy=Float.POSITIVE_INFINITY).usable(fix.elapsedNanos))
    }
    @Test fun exportPreservesMeasurementContextAndEvidenceLink() {
        val record=Record(id="geo",kind="location",target="aparelho",message="posição",details=fix.details())
        val observation=Record(kind="http",target="https://example.test",message="GET 200",details=mapOf("locationRecordId" to record.id))
        val text=AnalysisCopy.session(Investigation(records=listOf(record,observation)))
        assertTrue(text.contains("accuracyMeters"))
        assertTrue(text.contains("1970-01-01T00:00:01Z"))
        assertTrue(text.contains("locationRecordId"))
    }
}
