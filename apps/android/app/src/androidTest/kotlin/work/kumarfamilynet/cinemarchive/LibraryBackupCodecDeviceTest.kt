package work.kumarfamilynet.cinemarchive

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.ExistingLibrary
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.ParseResult

/**
 * Runs the codec on the REAL framework org.json (the JVM unit tests use JSON-java): exact opaque
 * numbers must survive parse → encode → re-parse and planCopy, ordinary ratings must stay usable,
 * and surrogate handling must produce valid UTF-8 — on an actual Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class LibraryBackupCodecDeviceTest {
    private fun titleJson(extra: String) =
        """{"version":2,"titles":[{"id":"d1","tmdbId":5,"type":"movie","title":"Dev","year":2000,"genres":[],"status":"watched","rating":4.5,"tags":[],"addedAt":"2026-01-01","viewings":[]$extra}],"outings":[]}"""

    private fun parse(text: String): LibraryBackupCodec.BackupDocument {
        val r = LibraryBackupCodec.parse(text)
        assertTrue("expected Success but was $r", r is ParseResult.Success)
        return (r as ParseResult.Success).document
    }

    @Test
    fun exactNumbersRoundTripOnFrameworkJson() {
        val big = "9223372036854775809"
        val dec = "0.12345678901234567890123456789"
        val doc = parse(titleJson(""","futureBig":$big,"futureDec":$dec,"futureTrail":1.50"""))
        val t = doc.titles[0]
        assertEquals(BigInteger(big), t.get("futureBig"))
        assertEquals(BigDecimal(dec), t.get("futureDec"))

        val encoded = LibraryBackupCodec.encode(doc, "2026-10-08T00:00:00Z")
        assertTrue(encoded.contains(big))
        assertTrue(encoded.contains(dec))
        assertTrue(encoded.contains("1.50"))
        val again = parse(encoded).titles[0]
        assertEquals(BigInteger(big), again.get("futureBig"))
        assertEquals(BigDecimal(dec), again.get("futureDec"))

        var n = 0
        val plan = LibraryBackupCodec.planCopy(doc, ExistingLibrary()) { "dev-${++n}" }
        val ext = plan.titles.single().getJSONObject("ext")
        assertEquals(BigInteger(big), ext.get("futureBig"))
        assertEquals(BigDecimal(dec), ext.get("futureDec"))
    }

    @Test
    fun ordinaryRatingsStayUsable() {
        val doc = parse(titleJson(""))
        assertTrue(LibraryBackupCodec.validate(doc).none { it.fatal })
        var n = 0
        val plan = LibraryBackupCodec.planCopy(doc, ExistingLibrary()) { "dev-${++n}" }
        val row = plan.titles.single()
        assertEquals(4.5, row.getDouble("rating"), 0.0)
        assertTrue(LibraryBackupCodec.encode(doc, "2026-10-08T00:00:00Z").contains("4.5"))
    }

    @Test
    fun surrogatesProduceValidUtf8() {
        val ok = parse(titleJson(""","note2":"\ud83c\udfac clapper \u00e9""""))
        assertEquals("\uD83C\uDFAC clapper \u00e9", ok.titles[0].getString("note2"))
        val bytes = LibraryBackupCodec.encode(ok, "2026-10-08T00:00:00Z").toByteArray(Charsets.UTF_8)
        val back = LibraryBackupCodec.parseBytes(bytes)
        assertTrue(back is ParseResult.Success)
        assertEquals("\uD83C\uDFAC clapper \u00e9", (back as ParseResult.Success).document.titles[0].getString("note2"))

        for (bad in listOf("\ud800", "\udc00x", "\ud800\u0041")) {
            assertTrue("should reject $bad", LibraryBackupCodec.parse(titleJson(""","note2":"$bad"""")) is ParseResult.Failure)
        }
    }

    @Test
    fun hostileShapesAreRejectedNotThrown() {
        assertTrue(LibraryBackupCodec.parse("{\"titles\":[") is ParseResult.Failure)
        assertTrue(LibraryBackupCodec.parse("{\"titles\":[],\"titles\":[]}") is ParseResult.Failure) // duplicate key
        assertTrue(LibraryBackupCodec.parse("[] trailing") is ParseResult.Failure)
        assertFalse(LibraryBackupCodec.parse("{\"titles\":[]}") is ParseResult.Failure)
        val deep = "[".repeat(200) + "]".repeat(200)
        assertTrue(LibraryBackupCodec.parse(deep) is ParseResult.Failure)
    }

    @Test
    fun finiteRangeOnFrameworkJson() {
        assertTrue(LibraryBackupCodec.parse("""{"version":2,"titles":[],"x":1e999}""") is ParseResult.Failure)
        val d = parse("""{"version":2,"titles":[],"x":{"a":1e308,"c":${"9".repeat(60)}}}""")
        val x = d.extra.getJSONObject("x")
        assertEquals(0, BigDecimal("1e308").compareTo(x.get("a") as BigDecimal))
        assertEquals(BigInteger("9".repeat(60)), x.get("c"))
        val again = parse(LibraryBackupCodec.encode(d, "2026-10-08T00:00:00Z")).extra.getJSONObject("x")
        assertEquals(BigInteger("9".repeat(60)), again.get("c"))
    }
}
