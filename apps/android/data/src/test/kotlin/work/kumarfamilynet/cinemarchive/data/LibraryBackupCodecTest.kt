package work.kumarfamilynet.cinemarchive.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.BackupDocument
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.CopyPlan
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.ExistingLibrary
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.Limits
import work.kumarfamilynet.cinemarchive.data.LibraryBackupCodec.ParseResult

class LibraryBackupCodecTest {

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun fixtureDir(): File {
        val start = System.getProperty("user.dir") ?: "."
        var dir: File? = File(start).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/library-backup")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("Could not find docs/fixtures/library-backup walking up from $start")
        throw IllegalStateException()
    }

    private fun fixtureBytes(name: String): ByteArray = File(fixtureDir(), name).readBytes()
    private fun fixture(name: String): String = String(fixtureBytes(name), Charsets.UTF_8)

    private fun success(text: String): ParseResult.Success {
        val r = LibraryBackupCodec.parse(text)
        assertTrue("expected Success but was $r", r is ParseResult.Success)
        return r as ParseResult.Success
    }

    private fun failure(text: String): String {
        val r = LibraryBackupCodec.parse(text)
        assertTrue("expected Failure for <${text.take(80)}> but was $r", r is ParseResult.Failure)
        return (r as ParseResult.Failure).message
    }

    private fun load(name: String): BackupDocument = success(fixture(name)).document

    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun canonList(l: List<JSONObject>) = l.joinToString(",", "[", "]") { canon(it) }

    private fun doc(
        titles: List<JSONObject> = emptyList(),
        outings: List<JSONObject> = emptyList(),
        lists: List<JSONObject> = emptyList(),
    ) = BackupDocument(2, null, null, null, titles, outings, lists, null, JSONObject())

    private fun title(id: String, tmdb: Int, type: String = "movie") = JSONObject()
        .put("id", id).put("tmdbId", tmdb).put("type", type).put("title", "T$tmdb").put("status", "watched")

    private fun outing(id: String, titleId: String) = JSONObject()
        .put("id", id).put("titleId", titleId)
        .put("showtime", "2026-01-02T19:00:00Z").put("endsAt", "2026-01-02T21:00:00Z").put("status", "scheduled")

    private fun counter(): () -> String { var n = 0; return { "new-${++n}" } }

    private fun plan(d: BackupDocument, existing: ExistingLibrary = ExistingLibrary()): CopyPlan =
        LibraryBackupCodec.planCopy(d, existing, counter())

    private fun planFixture(name: String, existing: ExistingLibrary = ExistingLibrary()): CopyPlan = plan(load(name), existing)

    private fun fatalPaths(d: BackupDocument) = LibraryBackupCodec.validate(d).filter { it.fatal }.map { it.path }

    private fun isUnder(path: String, prefix: String) = path == prefix || path.startsWith("$prefix.") || path.startsWith("$prefix[")

    private fun assertFatalAt(paths: List<String>, prefix: String) =
        assertTrue("expected fatal at $prefix but got $paths", paths.any { isUnder(it, prefix) })

    private fun assertNotFatalAt(paths: List<String>, prefix: String) =
        assertFalse("unexpected fatal at $prefix in $paths", paths.any { isUnder(it, prefix) })

    private fun collectIds(v: Any?, out: MutableSet<String>) {
        when (v) {
            is JSONObject -> for (k in v.keys()) {
                if (k == "physicalMedia" || k == "ext") continue
                val child = v.get(k)
                if (k == "id" && child is String) out += child
                collectIds(child, out)
            }
            is JSONArray -> for (i in 0 until v.length()) collectIds(v.get(i), out)
        }
    }

    private fun planText(p: CopyPlan) = JSONArray(p.titles).toString() + JSONArray(p.outings).toString() + JSONArray(p.lists).toString()

    private fun warnPaths(p: CopyPlan) = p.report.warnings.map { it.path }
    private fun rejectPaths(p: CopyPlan) = p.report.rejections.map { it.path }

    // ── parse: envelope / formats ───────────────────────────────────────────────────────────

    @Test fun parseV1() {
        val r = success(fixture("v1-web-export.json"))
        assertEquals(1, r.sourceVersion)
        assertFalse(r.bareArray)
        assertEquals(2, r.document.titles.size)
        assertEquals(1, r.document.outings.size)
        assertEquals(0, r.document.lists.size)
    }

    @Test fun parseBareArray() {
        val r = success(fixture("bare-array.json"))
        assertTrue(r.bareArray)
        assertEquals(1, r.sourceVersion)
        assertEquals(2, r.document.titles.size)
        assertEquals(0, r.document.outings.size)
    }

    @Test fun parseV2() {
        val r = success(fixture("v2-android-export.json"))
        val d = r.document
        assertEquals(2, r.sourceVersion)
        assertFalse(r.bareArray)
        assertEquals("cinemarchive-library", d.format)
        assertEquals("2026-10-08T12:30:00Z", d.exportedAt)
        assertEquals("android", d.client!!.getString("platform"))
        assertEquals(1, d.titles.size)
        assertEquals(1, d.outings.size)
        assertEquals(1, d.lists.size)
        assertNotNull(d.localOnly)
        assertTrue(d.extra.has("futureTopLevel"))
        assertFalse(d.extra.has("titles"))
        assertEquals(2, d.titles[0].getJSONArray("tags").length())
    }

    @Test fun parseMissingOutingsLists() {
        val d = success("""{"version":1,"titles":[]}""").document
        assertTrue(d.outings.isEmpty())
        assertTrue(d.lists.isEmpty())
        val n = success("""{"titles":[],"outings":null,"lists":null}""").document
        assertTrue(n.outings.isEmpty() && n.lists.isEmpty())
    }

    @Test fun parseVersion3() {
        val r = LibraryBackupCodec.parse("""{"version":3,"titles":[]}""")
        assertTrue(r is ParseResult.Failure)
        assertTrue((r as ParseResult.Failure).message.contains("3"))
    }

    @Test fun parseBadVersions() {
        for (v in listOf("0", "-1", "\"2\"", "2.0", "1.5", "null", "true", "[]", "1e0")) {
            failure("""{"version":$v,"titles":[]}""")
        }
        assertEquals(1, success("""{"version":1,"titles":[]}""").sourceVersion)
        assertEquals(1, success("""{"titles":[]}""").sourceVersion)
        assertEquals(2, success("""{"version":2,"titles":[]}""").sourceVersion)
    }

    @Test fun parseFormatChecks() {
        assertTrue(failure("""{"format":"other-app","version":2,"titles":[]}""").contains("not a CinemArchive"))
        failure("""{"format":5,"titles":[]}""")
        failure("""{"format":["cinemarchive-library"],"titles":[]}""")
        assertNull(success("""{"format":"","titles":[]}""").document.format)
        assertEquals("cinemarchive-library", success("""{"format":"cinemarchive-library","titles":[]}""").document.format)
    }

    @Test fun parseEnvelopeTypes() {
        failure("""{"titles":[],"outings":{}}""")
        failure("""{"titles":[],"outings":[1]}""")
        failure("""{"titles":[],"lists":"x"}""")
        failure("""{"titles":[],"lists":[[]]}""")
        failure("""{"titles":[],"client":"x"}""")
        failure("""{"titles":[],"localOnly":[]}""")
        failure("""{"titles":[],"exportedAt":5}""")
        failure("""{"titles":null}""")
        failure("""{"titles":{}}""")
        failure("42")
        failure("\"str\"")
        failure("null")
    }

    @Test fun parseInvalidJson() {
        assertTrue(LibraryBackupCodec.parse("{\"titles\": [") is ParseResult.Failure)
    }

    @Test fun parseNonObjectTitle() {
        assertTrue(LibraryBackupCodec.parse("""{"titles":[1]}""") is ParseResult.Failure)
        assertTrue(LibraryBackupCodec.parse("""[{"a":1}, "x"]""") is ParseResult.Failure)
    }

    @Test fun parseNoTitlesArray() {
        assertTrue(LibraryBackupCodec.parse("""{"version":1}""") is ParseResult.Failure)
    }

    // ── parse: strict JSON ──────────────────────────────────────────────────────────────────

    @Test fun strictTrailingFixture() {
        assertTrue(failure(fixture("trailing-garbage.json")).contains("JSON"))
    }

    @Test fun strictRejectsMalformed() {
        val prefix = """{"version":1,"titles":[],"x":"""
        val bad = listOf(
            """{"version":1,"titles":[]} x""",
            """{"version":1,"titles":[]}{}""",
            """[]]""",
            """{"version":1,"titles":[],}""",
            """{"version":1,"titles":[,]}""",
            """{/*c*/"version":1,"titles":[]}""",
            """{"version":1,"titles":[]} // c""",
            """{'version':1,'titles':[]}""",
            """{version:1,titles:[]}""",
            """{"version":NaN,"titles":[]}""",
            """{"version":Infinity,"titles":[]}""",
            """{"version":-Infinity,"titles":[]}""",
            """{"version":01,"titles":[]}""",
            """{"version":-01,"titles":[]}""",
            """{"version":+1,"titles":[]}""",
            """{"version":.5,"titles":[]}""",
            """{"version":1.,"titles":[]}""",
            """{"version":1e,"titles":[]}""",
            """{"version":0x10,"titles":[]}""",
            """{"version":tru,"titles":[]}""",
            """{"version":truex,"titles":[]}""",
            """{"titles":[],"titles":[]}""",
            prefix + "\"tab\there\"}",
            prefix + "\"nl\nhere\"}",
            prefix + "\"nul\u0000here\"}",
            prefix + """"\x41"}""",
            prefix + """"\u12G4"}""",
            prefix + """"\u12"}""",
            prefix + """"unterminated}""",
            prefix + """"\""",
            prefix + """1e999}""",
            "",
            "   ",
            " {\"titles\":[]}",
            "{\"titles\":[]} ",
            "{\"titles\":[]} ﻿",
        )
        for (text in bad) failure(text)
    }

    @Test fun strictAcceptsValid() {
        val ws = " \t\r\n"
        val x = success(
            ws + """{"version":2,"titles":[],"x":{"s":"é\n\/\"\\","big":12345678901234567890,"neg":-0,"f":1.5e2,"t":true,"f2":false,"n":null,"a":[ 1 , 2 ],"e":{},"z":[]}}""" + ws,
        ).document.extra.getJSONObject("x")
        assertEquals("é\n/\"\\", x.getString("s"))
        assertEquals(java.math.BigInteger("12345678901234567890"), x.get("big"))
        assertEquals(0L, x.get("neg"))
        assertEquals(150.0, (x.get("f") as Number).toDouble(), 0.0)
        assertEquals(true, x.get("t"))
        assertEquals(false, x.get("f2"))
        assertTrue(x.isNull("n"))
        assertTrue(x.getJSONArray("a").get(0) is Long)
        assertEquals(0, x.getJSONObject("e").length())
        assertEquals(0, x.getJSONArray("z").length())
    }

    @Test fun strictDuplicateKeys() {
        assertTrue(failure(fixture("duplicate-keys.json")).contains("duplicate"))
        failure("""{"titles":[{"id":"a","id":"b"}]}""")
        failure("""{"titles":[],"x":{"k":1,"k":1}}""")
    }

    @Test fun strictBom() {
        val body = """{"version":1,"titles":[]}"""
        assertTrue(LibraryBackupCodec.parse("﻿" + body) is ParseResult.Success)
        failure("﻿﻿" + body)
        failure("{\"version\":﻿1,\"titles\":[]}")
    }

    @Test fun deepNestingIsBounded() {
        failure("[".repeat(100_000) + "]".repeat(100_000))
        failure("{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000))
        failure("[".repeat(100_000))
        val head = """{"titles":[],"x":"""
        assertTrue(failure(head + "[".repeat(70) + "]".repeat(70) + "}").contains("deep"))
        success(head + "[".repeat(60) + "]".repeat(60) + "}")
    }

    @Test fun nodeCountIsBounded() {
        val text = """{"titles":[],"x":[1,2,3,4,5,6,7,8,9]}"""
        assertTrue(LibraryBackupCodec.parseWithLimits(text, 5, 64) is ParseResult.Failure)
        assertTrue(LibraryBackupCodec.parseWithLimits(text, 100, 64) is ParseResult.Success)
    }

    // ── bytes / stream ──────────────────────────────────────────────────────────────────────

    @Test fun bytesOverMax() {
        val body = ByteArray(500) { 'a'.code.toByte() }
        assertTrue(LibraryBackupCodec.parseBytes(body, maxBytes = 100) is ParseResult.Failure)
    }

    @Test fun bytesStripBom() {
        val json = """{"version":1,"titles":[]}""".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json
        assertTrue(LibraryBackupCodec.parseBytes(withBom) is ParseResult.Success)
        val twice = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + withBom
        assertTrue(LibraryBackupCodec.parseBytes(twice) is ParseResult.Failure)
    }

    @Test fun bytesNormalFile() {
        assertTrue(LibraryBackupCodec.parseBytes(fixtureBytes("v1-web-export.json")) is ParseResult.Success)
    }

    @Test fun bytesInvalidUtf8() {
        val cases = listOf(
            byteArrayOf(0x7B, 0xFF.toByte(), 0x7D),
            byteArrayOf(0x7B, 0xC0.toByte(), 0xAF.toByte(), 0x7D),
            byteArrayOf(0x7B, 0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0x7B, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x7D),
        )
        for (c in cases) {
            val r = LibraryBackupCodec.parseBytes(c)
            assertTrue(r is ParseResult.Failure)
            assertTrue((r as ParseResult.Failure).message.contains("UTF-8"))
        }
    }

    @Test fun bytesMultibyteOk() {
        val text = """{"version":1,"titles":[{"id":"u","tmdbId":1,"type":"movie","title":"Amélie 映画"}]}"""
        val d = (LibraryBackupCodec.parseBytes(text.toByteArray(Charsets.UTF_8)) as ParseResult.Success).document
        assertEquals("Amélie 映画", d.titles[0].getString("title"))
    }

    private class GuardedStream(private val limit: Int) : InputStream() {
        var served = 0
        override fun read(): Int = throw IOException("single-byte read not expected")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) throw IOException("read beyond allowed limit")
            val n = minOf(len, limit - served)
            for (i in 0 until n) b[off + i] = 'a'.code.toByte()
            served += n
            return n
        }
    }

    @Test fun streamStopsEarly() {
        val maxBytes = 100
        val stream = GuardedStream(maxBytes + 16 * 1024)
        val r = LibraryBackupCodec.parseStream(stream, maxBytes)
        assertTrue(r is ParseResult.Failure)
        assertTrue("read ${stream.served}", stream.served <= maxBytes + 16 * 1024)
    }

    @Test fun streamBomAndNormal() {
        val ok = LibraryBackupCodec.parseStream(ByteArrayInputStream(fixtureBytes("v2-android-export.json")))
        assertTrue(ok is ParseResult.Success)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + fixtureBytes("bare-array.json")
        assertTrue(LibraryBackupCodec.parseStream(ByteArrayInputStream(bom)) is ParseResult.Success)
    }

    @Test fun streamIoErrorIsFailure() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
        }
        assertTrue(LibraryBackupCodec.parseStream(broken) is ParseResult.Failure)
    }

    @Test fun sha256OfOriginalBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LibraryBackupCodec.sha256Hex("abc".toByteArray(Charsets.UTF_8)))
        assertEquals(64, LibraryBackupCodec.sha256Hex(ByteArray(0)).length)
        val bytes = fixtureBytes("v1-web-export.json")
        val reencoded = LibraryBackupCodec.encode(load("v1-web-export.json"), "2026-10-09T00:00:00Z").toByteArray(Charsets.UTF_8)
        assertNotEquals(LibraryBackupCodec.sha256Hex(bytes), LibraryBackupCodec.sha256Hex(reencoded))
    }

    // ── totality ────────────────────────────────────────────────────────────────────────────

    @Test fun truncationsNeverThrow() {
        val text = fixture("v2-android-export.json")
        var n = 0
        while (n <= text.length) {
            val r = LibraryBackupCodec.parse(text.substring(0, n))
            if (r is ParseResult.Success) {
                LibraryBackupCodec.validate(r.document)
                LibraryBackupCodec.planCopy(r.document, ExistingLibrary(), counter())
            }
            n += 5
        }
        val bytes = fixtureBytes("hostile.json")
        for (len in 0..bytes.size step 11) LibraryBackupCodec.parseBytes(bytes.copyOf(len))
    }

    @Test fun weirdDocumentsNeverThrow() {
        val docs = listOf(
            """{"titles":[{}]}""",
            """{"titles":[{"id":null,"tmdbId":null,"type":null,"title":null}]}""",
            """{"titles":[{"id":"a","tmdbId":1,"type":"movie","title":"T","seasons":[null,1,"x",[]],"viewings":[[],null,5]}]}""",
            """{"titles":[{"id":"a","tmdbId":1,"type":"tv","title":"T","seasons":[{"seasonNumber":1,"episodes":[null,[],{"episodeNumber":1,"watchEvents":[1],"ratings":{},"reviews":"x"}]}]}]}""",
            """{"titles":[],"outings":[{}],"lists":[{}]}""",
            """{"titles":[],"outings":[{"id":"o","titleId":5,"showtime":1,"endsAt":null,"status":[]}],"lists":[{"id":"l","name":"n","items":[1,null,{"titleId":5}]}]}""",
            """{"titles":[{"id":"a","tmdbId":1,"type":"movie","title":"T","viewings":[{"id":"v","outingId":{"x":1}}]}],"outings":[{"id":"o","titleId":"a","showtime":"2026-01-01T00:00:00Z","endsAt":"2026-01-01T01:00:00Z","status":"completed","completedViewingId":{"a":1}}]}""",
            """{"titles":[{"id":"a","tmdbId":2147483648,"type":"movie","title":"T"}]}""",
            """{"titles":[{"id":"a","tmdbId":1,"type":"movie","title":"T","rating":1e999}]}""",
        )
        for (text in docs) {
            val r = LibraryBackupCodec.parse(text)
            if (r !is ParseResult.Success) continue
            LibraryBackupCodec.validate(r.document)
            val p = LibraryBackupCodec.planCopy(r.document, ExistingLibrary(), counter())
            assertEquals(r.document.titles.size, p.report.titlesNew + p.report.titlesRejected + p.report.titlesSkippedExisting)
            assertEquals(r.document.outings.size, p.report.outingsNew + p.report.outingsRejected)
        }
    }

    @Test fun planNeverThrowsOnIdSource() {
        val p = LibraryBackupCodec.planCopy(doc(listOf(title("a", 1))), ExistingLibrary()) { throw IllegalStateException("boom") }
        assertTrue(p.titles.isEmpty())
        assertTrue(p.report.rejections.any { it.fatal && it.path == "\$" })
    }

    // ── lossless / encode ───────────────────────────────────────────────────────────────────

    private fun roundTrip(d: BackupDocument): BackupDocument =
        success(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z")).document

    @Test fun roundTripV1Unknown() {
        val d = load("v1-web-export.json")
        val back = roundTrip(d)
        assertEquals(canonList(d.titles), canonList(back.titles))
        assertEquals(canonList(d.outings), canonList(back.outings))
        val matrix = back.titles[0]
        assertEquals("me", matrix.getJSONObject("futureField").getString("keep"))
        assertEquals("https://example.com/watch/matrix", matrix.getString("customWatchUrl"))
        assertEquals("Steelbook", matrix.getJSONArray("physicalMedia").getJSONObject(0).getString("edition"))
        assertEquals("pm-1", matrix.getJSONArray("physicalMedia").getJSONObject(0).getString("id"))
    }

    @Test fun roundTripV2All() {
        val d = load("v2-android-export.json")
        val back = roundTrip(d)
        assertEquals(canonList(d.titles), canonList(back.titles))
        assertEquals(canonList(d.outings), canonList(back.outings))
        assertEquals(canonList(d.lists), canonList(back.lists))
        assertEquals(canon(d.localOnly), canon(back.localOnly))
        assertEquals(canon(d.client), canon(back.client))
        assertEquals(canon(d.extra), canon(back.extra))
        assertEquals("""{"futureTopLevel":{"kept":true}}""", canon(back.extra))
    }

    @Test fun roundTripArchiveKeepsEverything() {
        val d = load("ticket-path-and-ext.json")
        val back = roundTrip(d)
        assertEquals(canonList(d.titles), canonList(back.titles))
        assertEquals(canonList(d.outings), canonList(back.outings))
        assertEquals(canonList(d.lists), canonList(back.lists))
        // the ARCHIVE is lossless: device path, descriptors and unknown fields all survive verbatim
        val o = back.outings[0]
        assertEquals("/data/user/0/app/files/tickets/x.jpg", o.getString("ticketImagePath"))
        assertTrue(o.has("ticketAttachment"))
        assertTrue(back.titles[0].getJSONObject("futureTitleField").has("receipts"))
    }

    @Test fun roundTripHostileArchive() {
        val d = load("hostile.json")
        val back = roundTrip(d)
        assertEquals(canonList(d.titles), canonList(back.titles))
        assertEquals(canonList(d.outings), canonList(back.outings))
        assertEquals(canonList(d.lists), canonList(back.lists))
    }

    @Test fun encodeDeterministic() {
        val d = load("v1-web-export.json")
        assertEquals(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z"), LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z"))
        val again = success(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z")).document
        assertEquals(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z"), LibraryBackupCodec.encode(again, "2026-10-09T00:00:00Z"))
    }

    @Test fun encodeShape() {
        val text = LibraryBackupCodec.encode(load("v1-web-export.json"), "2026-10-09T00:00:00Z")
        assertTrue(text.startsWith("{\n  \"exportedAt\": \"2026-10-09T00:00:00Z\",\n  \"format\": \"cinemarchive-library\",\n"))
        assertTrue(text.endsWith("}\n"))
        assertFalse(text.endsWith("\n\n"))
        val a = text.indexOf("\"addedAt\"")
        val c = text.indexOf("\"customWatchUrl\"")
        val f = text.indexOf("\"futureField\"")
        assertTrue(a in 0 until c && c < f)
        assertTrue(text.contains("\n    {\n      \"addedAt\""))
        val re = success(text)
        assertEquals(2, re.sourceVersion)
        assertEquals("cinemarchive-library", re.document.format)
        assertEquals("2026-10-09T00:00:00Z", re.document.exportedAt)
    }

    @Test fun encodeClientOverride() {
        val d = load("v2-android-export.json")
        val out = success(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z", JSONObject().put("platform", "test"))).document
        assertEquals("test", out.client!!.getString("platform"))
    }

    @Test fun encodeBareArrayIsV2() {
        val text = LibraryBackupCodec.encode(load("bare-array.json"), "2026-10-09T00:00:00Z")
        val re = success(text)
        assertFalse(re.bareArray)
        assertEquals(2, re.sourceVersion)
        assertEquals(2, re.document.titles.size)
    }

    @Test fun encodeQuoting() {
        val t = title("q", 1).put("notes", "a/b </script> \"q\" \\ \u0001 \u001f tab\t nl\n é 日本")
        val text = LibraryBackupCodec.encode(doc(listOf(t)), "2026-10-09T00:00:00Z")
        assertTrue(text.contains("a/b </script>")) // slashes are never escaped
        assertTrue(text.contains("\\u0001") && text.contains("\\u001f"))
        val back = success(text).document.titles[0]
        assertEquals(t.getString("notes"), back.getString("notes"))
    }

    @Test fun encodeNumbers() {
        val extra = JSONObject().put("w", 5.0).put("d", 4.5).put("big", 1e21).put("neg", -0.0).put("l", 7L).put("i", 3)
        val d = BackupDocument(2, null, null, null, emptyList(), emptyList(), emptyList(), null, extra)
        val back = success(LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z")).document.extra
        assertEquals(5L, back.get("w"))
        assertEquals(4.5, (back.get("d") as Number).toDouble(), 0.0)
        assertEquals(1e21, (back.get("big") as Number).toDouble(), 0.0)
        assertEquals(0L, back.get("neg"))
        assertEquals(7L, back.get("l"))
        assertEquals(3L, back.get("i"))
    }

    @Test fun encodeDepthGuard() {
        var node = JSONObject()
        repeat(200) { node = JSONObject().put("n", node) }
        val d = BackupDocument(2, null, null, null, emptyList(), emptyList(), emptyList(), null, JSONObject().put("deep", node))
        try {
            LibraryBackupCodec.encode(d, "2026-10-09T00:00:00Z")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected: only programmatically built trees can be this deep
        }
    }

    // ── validate ────────────────────────────────────────────────────────────────────────────

    @Test fun validateHostilePaths() {
        val d = load("hostile.json")
        val issues = LibraryBackupCodec.validate(d)
        val fatal = issues.filter { it.fatal }.map { it.path }
        for (i in 0..5) assertFatalAt(fatal, "titles[$i]") // both rows sharing id "dup" are rejected
        for (i in 0..2) assertFatalAt(fatal, "outings[$i]")
        assertFatalAt(fatal, "lists[1]")
        assertTrue(fatal.contains("titles[0].id"))
        assertTrue(fatal.contains("titles[1].id"))
        assertTrue(fatal.contains("titles[2].id"))
        assertTrue(fatal.contains("titles[3].rating"))
        assertTrue(issues.all { it.message.isNotBlank() })
    }

    @Test fun validateHostileOkRows() {
        val fatal = fatalPaths(load("hostile.json"))
        for (clean in listOf("titles[6]", "lists[0]")) assertNotFatalAt(fatal, clean)
    }

    @Test fun validateCleanFixtures() {
        for (name in listOf("v1-web-export.json", "undated-and-companions.json", "bare-array.json", "v2-android-export.json")) {
            val issues = LibraryBackupCodec.validate(load(name))
            assertTrue("$name: $issues", issues.none { it.fatal })
        }
    }

    @Test fun validateWarningsNonFatal() {
        val t = title("t1", 1).put("viewings", JSONArray().put(JSONObject().put("id", "v1").put("date", "someday")))
        val o = outing("o1", "t1").put("format", "Laser")
        val issues = LibraryBackupCodec.validate(doc(listOf(t), listOf(o)))
        assertTrue(issues.none { it.fatal })
        assertTrue(issues.any { !it.fatal && it.path == "outings[0].format" })
        assertTrue(issues.any { !it.fatal && it.path == "titles[0].viewings[0].date" })
        val p = plan(doc(listOf(t), listOf(o)))
        assertEquals(1, p.report.titlesNew)
        assertEquals(1, p.report.outingsNew)
        assertEquals(2, p.report.warnings.size)
        assertEquals("Laser", p.outings[0].getString("format"))
        assertEquals("someday", p.titles[0].getJSONArray("viewings").getJSONObject(0).getString("date"))
    }

    @Test fun validateTmdbStrict() {
        val fatal = fatalPaths(load("float-tmdb.json"))
        for (i in 0..3) assertFatalAt(fatal, "titles[$i]")
        assertNotFatalAt(fatal, "titles[4]")
        val p = planFixture("float-tmdb.json")
        assertEquals(1, p.report.titlesNew)
        assertEquals(4, p.report.titlesRejected)
        // programmatic documents: Int/Long are integers, Double/Float/BigDecimal are not
        assertTrue(fatalPaths(doc(listOf(title("a", 1)))).isEmpty())
        assertTrue(fatalPaths(doc(listOf(JSONObject().put("id", "a").put("tmdbId", 5L).put("type", "movie").put("title", "x")))).isEmpty())
        assertFatalAt(fatalPaths(doc(listOf(JSONObject().put("id", "a").put("tmdbId", 5.0).put("type", "movie").put("title", "x")))), "titles[0]")
        assertFatalAt(fatalPaths(doc(listOf(JSONObject().put("id", "a").put("tmdbId", 2147483648L).put("type", "movie").put("title", "x")))), "titles[0]")
    }

    @Test fun validateMalformedContainers() {
        val d = load("malformed-containers.json")
        val fatal = fatalPaths(d)
        for (i in 0..7) assertFatalAt(fatal, "titles[$i]")
        assertNotFatalAt(fatal, "titles[8]")
        assertTrue(fatal.contains("titles[0].seasons"))
        assertTrue(fatal.contains("titles[1].viewings"))
        assertTrue(fatal.contains("titles[2].tags"))
        assertTrue(fatal.contains("titles[3].seasons[0].episodes"))
        assertTrue(fatal.contains("titles[4].seasons[0].episodes[0].watchEvents"))
        assertTrue(fatal.contains("titles[5].viewings[0].companions"))
        assertTrue(fatal.contains("titles[6].genres"))
        assertTrue(fatal.contains("titles[7].tags[0]"))
        assertFatalAt(fatal, "outings[0]")
        assertFatalAt(fatal, "outings[1]")
        assertFatalAt(fatal, "lists[0]")
        val p = plan(d)
        assertEquals(1, p.report.titlesNew)
        assertEquals(8, p.report.titlesRejected)
        assertEquals(0, p.report.outingsNew)
        assertEquals(2, p.report.outingsRejected)
        assertEquals(1, p.report.listsRejected)
        assertEquals(0, p.report.listsNew)
        assertTrue(p.report.rejections.all { it.fatal && it.message.isNotBlank() && it.path.isNotEmpty() })
    }

    @Test fun validateNaturalKeys() {
        val d = load("duplicate-natural-keys.json")
        val fatal = fatalPaths(d)
        for (i in listOf(0, 1, 3, 4, 5, 6, 7, 8, 9)) assertFatalAt(fatal, "titles[$i]")
        assertNotFatalAt(fatal, "titles[2]")
        assertTrue(fatal.contains("titles[0].seasons[1].seasonNumber"))
        assertTrue(fatal.contains("titles[1].seasons[0].episodes[1].episodeNumber"))
        assertTrue(fatal.contains("titles[3].seasons[0].episodes[0].ratings[0].rating"))
        assertTrue(fatal.contains("titles[4].seasons[0].episodes[0].ratings[0].ratedAt"))
        assertTrue(fatal.contains("titles[5].seasons[0].episodes[0].reviews[0].reviewText"))
        assertTrue(fatal.contains("titles[6].viewings[0].titleId"))
        assertTrue(fatal.contains("titles[7].seasons[0].seasonNumber"))
        assertTrue(fatal.contains("titles[8].seasons[0].episodes[0].episodeNumber"))
        assertTrue(fatal.contains("titles[9].seasons[0].seasonNumber"))
        val p = plan(d)
        assertEquals(1, p.report.titlesNew)
        assertEquals(9, p.report.titlesRejected)
        // season 0 / episode 0 and the same episode number in different seasons are valid
        val seasons = p.titles.single().getJSONArray("seasons")
        assertEquals(2, seasons.length())
        assertEquals(0, seasons.getJSONObject(0).getInt("seasonNumber"))
    }

    @Test fun validateUndatedWatchEventAllowed() {
        val p = planFixture("duplicate-natural-keys.json")
        val ev = p.titles.single().getJSONArray("seasons").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getJSONArray("watchEvents")
        assertFalse(ev.getJSONObject(0).has("watchedAt"))
    }

    @Test fun validateDuplicateTitleIdsBothRejected() {
        val d = doc(listOf(title("same", 1), title("same", 2), title("ok", 3)))
        val fatal = fatalPaths(d)
        assertFatalAt(fatal, "titles[0]")
        assertFatalAt(fatal, "titles[1]")
        assertNotFatalAt(fatal, "titles[2]")
        val p = plan(d)
        assertEquals(1, p.report.titlesNew)
        assertEquals(2, p.report.titlesRejected)
        assertEquals(2, p.report.ambiguousIdRows)
    }

    @Test fun validateNestedAmbiguousAcrossTitles() {
        fun tv(id: String, tmdb: Int, watchId: String) = title(id, tmdb, "tv").put(
            "seasons",
            JSONArray().put(
                JSONObject().put("id", "s-$id").put("seasonNumber", 1).put(
                    "episodes",
                    JSONArray().put(
                        JSONObject().put("id", "e-$id").put("episodeNumber", 1)
                            .put("watchEvents", JSONArray().put(JSONObject().put("id", watchId))),
                    ),
                ),
            ),
        )
        val d = doc(listOf(tv("a", 1, "shared-w"), tv("b", 2, "shared-w"), tv("c", 3, "unique-w")))
        val fatal = fatalPaths(d)
        assertFatalAt(fatal, "titles[0]")
        assertFatalAt(fatal, "titles[1]")
        assertNotFatalAt(fatal, "titles[2]")
        assertTrue(fatal.contains("titles[0].seasons[0].episodes[0].watchEvents[0].id"))
        assertEquals(2, plan(d).report.ambiguousIdRows)
    }

    @Test fun ambiguousIdsFixture() {
        val d = load("ambiguous-ids.json")
        val fatal = fatalPaths(d)
        for (i in 0..3) assertFatalAt(fatal, "titles[$i]")
        assertNotFatalAt(fatal, "titles[4]")
        for (i in listOf(0, 2, 3, 4, 5)) assertFatalAt(fatal, "outings[$i]")
        assertNotFatalAt(fatal, "outings[1]")
        assertNotFatalAt(fatal, "outings[6]")
        val p = plan(d)
        val r = p.report
        assertEquals(1, r.titlesNew)
        assertEquals(4, r.titlesRejected)
        assertEquals(2, r.outingsNew)
        assertEquals(5, r.outingsRejected)
        assertEquals(1, r.completedOutingsRejected)
        assertEquals(6, r.ambiguousIdRows)
        assertEquals(1, r.viewingOutingRefsDropped)
        assertEquals(1, r.listItemsNew)
        assertEquals(2, r.listItemsDropped)
        assertEquals(1, r.listsNew)
        // nothing binds to "whichever duplicate was seen first"
        val good = p.titles.single()
        assertFalse(good.getJSONArray("viewings").getJSONObject(0).has("outingId"))
        val comp = p.outings.single { it.has("completedViewingId") }
        assertEquals(good.getJSONArray("viewings").getJSONObject(0).getString("id"), comp.getString("completedViewingId"))
        assertEquals(good.getString("id"), p.lists.single().getJSONArray("items").getJSONObject(0).getString("titleId"))
        val text = planText(p)
        for (leaked in listOf("dupT", "vdup", "odup")) assertFalse("leaked $leaked", text.contains(leaked))
        assertTrue(rejectPaths(p).any { it == "outings[0]" || it.startsWith("outings[0].") })
        assertTrue(r.warnings.any { it.path == "titles[4].viewings[0].outingId" })
    }

    @Test fun completedWithoutHistoryFixture() {
        val d = load("completed-without-history.json")
        val fatal = fatalPaths(d)
        for (i in 0..2) assertFatalAt(fatal, "outings[$i]")
        assertNotFatalAt(fatal, "outings[3]")
        assertNotFatalAt(fatal, "outings[4]")
        val p = plan(d)
        val r = p.report
        assertEquals(2, r.titlesNew)
        assertEquals(2, r.outingsNew)
        assertEquals(3, r.outingsRejected)
        assertEquals(3, r.completedOutingsRejected)
        assertEquals(1, r.outingViewingRefsDropped)
        assertEquals(1, r.viewingOutingRefsDropped)
        val completed = p.outings.single { it.getString("status") == "completed" }
        val scheduled = p.outings.single { it.getString("status") == "scheduled" }
        assertFalse(scheduled.has("completedViewingId"))
        val v1 = p.titles[0].getJSONArray("viewings").getJSONObject(0)
        assertEquals(v1.getString("id"), completed.getString("completedViewingId"))
        assertEquals(completed.getString("id"), v1.getString("outingId"))
        assertEquals(p.titles[0].getString("id"), completed.getString("titleId"))
        // v2 pointed at an outing of another title: dropped, never cross-bound
        assertFalse(p.titles[1].getJSONArray("viewings").getJSONObject(0).has("outingId"))
        assertTrue(r.warnings.any { it.path == "outings[3].completedViewingId" })
        assertTrue(r.warnings.any { it.path == "titles[1].viewings[0].outingId" })
        // never a completed outing without its history
        assertTrue(p.outings.all { it.getString("status") != "completed" || it.has("completedViewingId") })
    }

    @Test fun skippedTitleExistingFixture() {
        val existing = ExistingLibrary(mapOf((603 to "movie") to "existing-1"))
        val p = planFixture("skipped-title-completed-outing.json", existing)
        val r = p.report
        assertEquals(1, r.titlesNew)
        assertEquals(2, r.titlesSkippedExisting)
        assertEquals(2, r.historyRowsOmittedForSkippedTitles)
        assertEquals(4, r.outingsNew)
        assertEquals(2, r.outingsRejected)
        assertEquals(2, r.completedOutingsRejected)
        assertEquals(3, r.outingsRetargeted)
        assertEquals(1, r.outingViewingRefsDropped)
        assertEquals(0, r.viewingOutingRefsDropped)
        assertTrue(rejectPaths(p).containsAll(listOf("outings[0]", "outings[4]")))
        for (path in listOf("outings[1].titleId", "outings[2].titleId", "outings[5].titleId", "outings[2].completedViewingId", "titles[0]", "titles[2]")) {
            assertTrue("missing warning $path in ${warnPaths(p)}", warnPaths(p).contains(path))
        }
        val b = p.titles.single()
        val bId = b.getString("id")
        assertEquals("existing-1", p.outings[0].getString("titleId")) // scheduled, re-pointed
        assertEquals("existing-1", p.outings[1].getString("titleId")) // missed, re-pointed
        assertFalse(p.outings[1].has("completedViewingId"))
        assertEquals(bId, p.outings[3].getString("titleId")) // second copy's scheduled outing follows the FIRST copy
        val bv = b.getJSONArray("viewings").getJSONObject(0)
        assertEquals(p.outings[2].getString("id"), bv.getString("outingId"))
        assertEquals(bv.getString("id"), p.outings[2].getString("completedViewingId"))
        // the second copy's history was NOT bound to the first title
        assertEquals(1, b.getJSONArray("viewings").length())
        val items = p.lists.single().getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals("existing-1", items.getJSONObject(0).getString("titleId"))
        assertEquals(bId, items.getJSONObject(1).getString("titleId"))
        assertEquals(1, r.listItemsRetargeted)
        assertEquals(1, r.listItemsDropped)
        assertTrue(warnPaths(p).contains("lists[0].items[0]"))
    }

    @Test fun skippedTitleDuplicateOnlyFixture() {
        val p = planFixture("skipped-title-completed-outing.json")
        val r = p.report
        assertEquals(2, r.titlesNew)
        assertEquals(1, r.titlesSkippedExisting)
        assertEquals(1, r.historyRowsOmittedForSkippedTitles)
        assertEquals(5, r.outingsNew)
        assertEquals(1, r.outingsRejected)
        assertEquals(1, r.completedOutingsRejected)
        assertEquals(1, r.outingsRetargeted)
        assertEquals(0, r.outingViewingRefsDropped)
        assertEquals(0, r.viewingOutingRefsDropped)
        assertEquals(listOf("outings[4]"), rejectPaths(p).filter { it.startsWith("outings") })
        val a = p.titles[0]
        val va = a.getJSONArray("viewings").getJSONObject(0)
        val aComp = p.outings.single { it.has("completedViewingId") && it.getString("titleId") == a.getString("id") && it.getString("status") == "completed" }
        assertEquals(va.getString("id"), aComp.getString("completedViewingId"))
        assertEquals(aComp.getString("id"), va.getString("outingId"))
    }

    @Test fun distinctIdsSameTmdbAreNotAmbiguous() {
        val d = doc(listOf(title("x1", 9), title("x2", 9)))
        assertTrue(fatalPaths(d).isEmpty())
        val p = plan(d)
        assertEquals(1, p.report.titlesNew)
        assertEquals(1, p.report.titlesSkippedExisting)
        assertEquals(0, p.report.ambiguousIdRows)
        assertEquals(0, p.report.titlesRejected)
    }

    @Test fun limitSeasons() {
        val seasons = JSONArray().also { a -> repeat(Limits.MAX_SEASONS_PER_TITLE + 1) { a.put(JSONObject()) } }
        assertFatalAt(fatalPaths(doc(listOf(title("t", 1).put("seasons", seasons)))), "titles[0].seasons")
    }

    @Test fun limitViewings() {
        val viewings = JSONArray().also { a -> repeat(Limits.MAX_VIEWINGS_PER_TITLE + 1) { a.put(JSONObject()) } }
        assertFatalAt(fatalPaths(doc(listOf(title("t", 1).put("viewings", viewings)))), "titles[0].viewings")
    }

    @Test fun limitTags() {
        val tags = JSONArray().also { a -> repeat(Limits.MAX_TAGS + 1) { a.put("tag$it") } }
        assertFatalAt(fatalPaths(doc(listOf(title("t", 1).put("tags", tags)))), "titles[0].tags")
        val okTags = JSONArray().also { a -> repeat(Limits.MAX_TAGS) { a.put("tag$it") } }
        assertTrue(fatalPaths(doc(listOf(title("t", 1).put("tags", okTags)))).isEmpty())
    }

    @Test fun limitString() {
        val long = "x".repeat(Limits.MAX_STRING + 1)
        assertFatalAt(fatalPaths(doc(listOf(title("t", 1).put("notes", long)))), "titles[0].notes")
        val atLimit = "x".repeat(Limits.MAX_STRING)
        assertTrue(fatalPaths(doc(listOf(title("t", 1).put("notes", atLimit)))).isEmpty())
    }

    // ── planCopy: v1 ────────────────────────────────────────────────────────────────────────

    @Test fun copyV1ReplacesAllIds() {
        val d = load("v1-web-export.json")
        val original = HashSet<String>()
        collectIds(JSONArray(d.titles), original)
        collectIds(JSONArray(d.outings), original)
        assertTrue(original.size >= 10)
        val p = plan(d)
        assertEquals(2, p.report.titlesNew)
        assertEquals(1, p.report.outingsNew)
        val text = planText(p)
        for (id in original) assertFalse("archive id leaked: $id", text.contains(id))
        assertFalse("physicalMedia id leaked", text.contains("pm-1"))
        val fresh = HashSet<String>()
        collectIds(JSONArray(p.titles), fresh)
        collectIds(JSONArray(p.outings), fresh)
        assertTrue(fresh.all { it.matches(Regex("new-\\d+")) })
        assertEquals(original.size, fresh.size)
    }

    @Test fun copyV1PhysicalMediaRegenerated() {
        val p = plan(load("v1-web-export.json"))
        val matrix = p.titles.single { it.getInt("tmdbId") == 603 }
        val pm = matrix.getJSONArray("physicalMedia").getJSONObject(0)
        assertTrue(pm.getString("id").matches(Regex("new-\\d+")))
        assertEquals("4K UHD", pm.getString("format"))
        assertEquals("Steelbook", pm.getString("edition"))
        assertEquals(0, p.report.untrustedFieldsDropped - 3) // nothing counted as dropped for the id
    }

    @Test fun copyV1References() {
        val p = plan(load("v1-web-export.json"))
        val matrix = p.titles.single { it.getInt("tmdbId") == 603 }
        val viewing = matrix.getJSONArray("viewings").getJSONObject(0)
        val outing = p.outings.single()
        assertEquals(matrix.getString("id"), viewing.getString("titleId"))
        assertEquals(matrix.getString("id"), outing.getString("titleId"))
        assertEquals(viewing.getString("id"), outing.getString("completedViewingId"))
        assertEquals(outing.getString("id"), viewing.getString("outingId"))
        assertNotEquals("22222222-2222-4222-8222-222222222222", viewing.getString("id"))
    }

    @Test fun copyV1StripsTickets() {
        val p = plan(load("v1-web-export.json"))
        val outing = p.outings.single()
        assertFalse(outing.has("ticketAttachment"))
        assertFalse(outing.has("ticketManaged"))
        assertFalse(outing.has("ticketImagePath")) // device-local path: removed and counted
        assertFalse(planText(p).contains("legacy.jpg"))
        assertEquals(3, p.report.untrustedFieldsDropped)
    }

    @Test fun copyV1Companions() {
        val p = plan(load("v1-web-export.json"))
        val matrix = p.titles.single { it.getInt("tmdbId") == 603 }
        val comps = matrix.getJSONArray("viewings").getJSONObject(0).getJSONArray("companions")
        assertEquals("Sam", comps.getJSONObject(0).getString("name"))
        assertEquals("99999999-9999-4999-8999-999999999999", comps.getJSONObject(0).getString("friendUserId"))
        assertEquals("Alex", comps.getJSONObject(1).getString("name"))
        assertFalse(comps.getJSONObject(1).has("friendUserId"))
        val oc = p.outings.single().getJSONArray("companions")
        assertEquals("99999999-9999-4999-8999-999999999999", oc.getJSONObject(0).getString("friendUserId"))
    }

    @Test fun copyV1UnknownFieldsGoToExt() {
        val p = plan(load("v1-web-export.json"))
        val matrix = p.titles.single { it.getInt("tmdbId") == 603 }
        assertFalse(matrix.has("futureField"))
        assertEquals("me", matrix.getJSONObject("ext").getJSONObject("futureField").getString("keep"))
        assertEquals("https://example.com/watch/matrix", matrix.getString("customWatchUrl")) // allowlisted
        assertTrue(matrix.getBoolean("inHomeCollection"))
    }

    // ── planCopy: tickets / ext ─────────────────────────────────────────────────────────────

    @Test fun copyTicketsAndExt() {
        val p = planFixture("ticket-path-and-ext.json")
        assertEquals(11, p.report.untrustedFieldsDropped)
        val o = p.outings.single()
        assertEquals("PAYLOAD-123", o.getString("ticketBarcodePayload"))
        assertEquals("qr", o.getString("ticketBarcodeFormat"))
        for (k in listOf("ticketImagePath", "ticketManaged", "ticketAttachment")) assertFalse("$k kept", o.has(k))
        assertFalse(o.has("outingFuture"))
        val oExt = o.getJSONObject("ext").getJSONObject("outingFuture")
        assertFalse(oExt.has("aliases"))
        assertEquals("inert-id", oExt.getString("id")) // inert data inside ext is kept verbatim
        val text = planText(p)
        for (leaked in listOf("tickets/x.jpg", "owner/x", "arch-receipt", "pm-archive", "item-id", "op2")) assertFalse("leaked $leaked", text.contains(leaked))
    }

    @Test fun copyExtStructure() {
        val p = planFixture("ticket-path-and-ext.json")
        val t = p.titles.single()
        assertFalse(t.has("futureTitleField"))
        val ext = t.getJSONObject("ext")
        assertEquals(1, ext.getInt("existing"))
        assertTrue(ext.getBoolean("futureTitleField2"))
        assertFalse(ext.has("userId"))
        val f = ext.getJSONObject("futureTitleField")
        assertEquals("yes", f.getString("keep"))
        assertFalse(f.has("receipts"))
        val nested = f.getJSONObject("nested")
        assertFalse(nested.has("TOKEN"))
        assertFalse(nested.getJSONObject("deep").has("outbox"))
        assertEquals(2, nested.getJSONObject("deep").getInt("ok"))
        val s = t.getJSONArray("seasons").getJSONObject(0)
        assertEquals(5, s.getJSONObject("ext").getInt("seasonFuture"))
        val e = s.getJSONArray("episodes").getJSONObject(0)
        assertEquals(2, e.getJSONObject("ext").getJSONArray("epFuture").length())
        assertEquals("w", e.getJSONArray("watchEvents").getJSONObject(0).getJSONObject("ext").getString("wFuture"))
        val v = t.getJSONArray("viewings").getJSONObject(0)
        assertFalse(v.has("operationId"))
        assertFalse(v.getJSONObject("ext").getJSONObject("viewingFuture").has("operationId"))
        val l = p.lists.single()
        assertEquals(1, l.getJSONObject("ext").getInt("listFuture"))
        val item = l.getJSONArray("items").getJSONObject(0)
        assertFalse(item.has("id"))
        assertEquals("i", item.getJSONObject("ext").getString("itemFuture"))
        // physicalMedia: identity regenerated, plain data retained, no token, stray scalar kept
        val pm = t.getJSONArray("physicalMedia")
        assertEquals(2, pm.length())
        val copy = pm.getJSONObject(0)
        assertTrue(copy.getString("id").matches(Regex("new-\\d+")))
        assertEquals("Steelbook", copy.getString("edition"))
        assertEquals("boxed", copy.getString("notes"))
        assertFalse(copy.has("token"))
        assertEquals("stray", pm.getString(1))
    }

    @Test fun copyExtCannotCarryAuthority() {
        val p = planFixture("ticket-path-and-ext.json")
        val ids = HashSet<String>()
        collectIds(JSONArray(p.titles), ids)
        collectIds(JSONArray(p.outings), ids)
        collectIds(JSONArray(p.lists), ids)
        // every identity-bearing id (outside inert ext) is a fresh planner id
        assertTrue(ids.all { it.matches(Regex("new-\\d+")) })
    }

    @Test fun copyUntrustedKeysCaseInsensitive() {
        val t = title("a", 1).put("USERID", "x").put("Outbox", JSONArray()).put("notes", "ok")
        val p = plan(doc(listOf(t)))
        assertEquals(2, p.report.untrustedFieldsDropped)
        assertFalse(p.titles.single().has("USERID"))
        assertFalse(p.titles.single().has("ext"))
    }

    @Test fun copyExtMergeAndCollision() {
        val merged = success("""{"titles":[{"id":"t","tmdbId":1,"type":"movie","title":"T","ext":{"foo":1,"bar":2},"foo":3,"baz":4}]}""").document
        val p = plan(merged)
        val ext = p.titles.single().getJSONObject("ext")
        assertEquals(3, ext.getInt("foo")) // top-level unknown wins
        assertEquals(2, ext.getInt("bar"))
        assertEquals(4, ext.getInt("baz"))
        assertTrue(warnPaths(p).contains("titles[0].ext.foo"))
        val notObject = plan(success("""{"titles":[{"id":"t","tmdbId":1,"type":"movie","title":"T","ext":"str"}]}""").document)
        assertEquals("str", notObject.titles.single().getJSONObject("ext").getString("ext"))
    }

    @Test fun copyPlanDeterministic() {
        val d = load("v1-web-export.json")
        assertEquals(planText(plan(d)), planText(plan(d)))
    }

    // ── planCopy: dedupe ────────────────────────────────────────────────────────────────────

    @Test fun copyDedupeExisting() {
        val existing = ExistingLibrary(mapOf((603 to "movie") to "existing-1"))
        val p = plan(load("v1-web-export.json"), existing)
        assertEquals(1, p.report.titlesNew)
        assertEquals(1, p.report.titlesSkippedExisting)
        assertEquals(1396, p.titles.single().getInt("tmdbId"))
        // the completed outing's viewing belongs to the skipped title: history is not restored,
        // so the completed outing is rejected rather than emitted without its required history
        assertTrue(p.outings.isEmpty())
        assertEquals(1, p.report.outingsRejected)
        assertEquals(1, p.report.completedOutingsRejected)
        assertEquals(1, p.report.historyRowsOmittedForSkippedTitles)
        assertTrue(p.report.rejections.any { it.path == "outings[0]" && it.message.isNotBlank() })
        assertTrue(warnPaths(p).contains("titles[0]"))
    }

    @Test fun copyDedupeInArchive() {
        val d = doc(listOf(title("a", 7), title("b", 7), title("c", 7, "tv")))
        val p = plan(d)
        assertEquals(2, p.titles.size)
        assertEquals(1, p.report.titlesSkippedExisting)
        assertEquals(setOf("movie", "tv"), p.titles.map { it.getString("type") }.toSet())
    }

    @Test fun copyInArchiveDuplicateFollowsFirst() {
        val a = title("a", 7)
        val b = title("b", 7)
        val o = outing("o", "b")
        val l = JSONObject().put("id", "l").put("name", "L").put("items", JSONArray().put(JSONObject().put("titleId", "b")))
        val p = plan(doc(listOf(a, b), listOf(o), listOf(l)))
        assertEquals(1, p.titles.size)
        assertEquals(p.titles[0].getString("id"), p.outings.single().getString("titleId"))
        assertEquals(p.titles[0].getString("id"), p.lists.single().getJSONArray("items").getJSONObject(0).getString("titleId"))
        assertEquals(1, p.report.outingsRetargeted)
        assertEquals(1, p.report.listItemsRetargeted)
    }

    // ── planCopy: undated / companions / lists ──────────────────────────────────────────────

    @Test fun copyUndatedStaysUndated() {
        val p = plan(load("undated-and-companions.json"))
        val movie = p.titles.single { it.getInt("tmdbId") == 11 }
        val vs = movie.getJSONArray("viewings")
        assertFalse(vs.getJSONObject(0).has("date"))
        assertEquals("2026-10-02", vs.getJSONObject(1).getString("date"))
        val ep = p.titles.single { it.getInt("tmdbId") == 22 }
            .getJSONArray("seasons").getJSONObject(0).getJSONArray("episodes").getJSONObject(0)
        val events = ep.getJSONArray("watchEvents")
        assertFalse(events.getJSONObject(0).has("watchedAt"))
        assertEquals("2026-10-03", events.getJSONObject(1).getString("watchedAt"))
    }

    @Test fun copyRatingZeroKept() {
        val p = plan(load("undated-and-companions.json"))
        val v = p.titles.single { it.getInt("tmdbId") == 11 }.getJSONArray("viewings").getJSONObject(1)
        assertTrue(v.has("rating"))
        assertEquals(0.0, v.getDouble("rating"), 0.0)
    }

    @Test fun copyCompanionsNormalized() {
        val p = plan(load("undated-and-companions.json"))
        val v = p.titles.single { it.getInt("tmdbId") == 11 }.getJSONArray("viewings").getJSONObject(0)
        assertEquals("""[{"name":"Robin"},{"friendUserId":"friend-1","name":"Kit"}]""", canon(v.getJSONArray("companions")))
    }

    @Test fun normalizeCompanionsDirect() {
        assertEquals(0, LibraryBackupCodec.normalizeCompanions("x").length())
        assertEquals(0, LibraryBackupCodec.normalizeCompanions(null).length())
        val raw = JSONArray().put("  A  ").put(JSONObject().put("name", " B ").put("friendUserId", "")).put(5).put("   ").put("x".repeat(Limits.MAX_SHORT + 50))
        val out = LibraryBackupCodec.normalizeCompanions(raw)
        assertEquals(3, out.length())
        assertEquals("A", out.getJSONObject(0).getString("name"))
        assertFalse(out.getJSONObject(1).has("friendUserId"))
        assertEquals(Limits.MAX_SHORT, out.getJSONObject(2).getString("name").length)
    }

    @Test fun copyListsCollapse() {
        val p = plan(load("undated-and-companions.json"))
        assertEquals(1, p.report.listsNew)
        assertEquals(1, p.report.listItemsNew)
        assertEquals(2, p.report.listItemsDropped)
        val list = p.lists.single()
        assertNotEquals("l1", list.getString("id"))
        assertEquals("Noir", list.getString("name"))
        val items = list.getJSONArray("items")
        assertEquals(1, items.length())
        assertEquals(p.titles.single { it.getInt("tmdbId") == 11 }.getString("id"), items.getJSONObject(0).getString("titleId"))
        assertEquals(1, p.report.unknownTopLevelKeysIgnored)
        assertFalse(planText(p).contains("futureTopLevel"))
        assertTrue(warnPaths(p).contains("lists[0].items[1]"))
        assertTrue(warnPaths(p).contains("lists[0].items[2]"))
    }

    // ── planCopy: hostile ───────────────────────────────────────────────────────────────────

    @Test fun copyHostileCounts() {
        val p = plan(load("hostile.json"))
        val r = p.report
        assertEquals(1, r.titlesNew) // only "ok": both rows sharing id "dup" are rejected
        assertEquals(6, r.titlesRejected)
        for (i in 0..5) assertTrue("no rejection for titles[$i]", r.rejections.any { it.fatal && it.path.startsWith("titles[$i]") && it.message.isNotBlank() })
        assertEquals(0, r.outingsNew)
        assertEquals(3, r.outingsRejected)
        assertEquals(1, r.completedOutingsRejected)
        assertEquals(2, r.ambiguousIdRows)
        assertEquals(1, r.viewingOutingRefsDropped)
        assertEquals(1, r.listsNew)
        assertEquals(1, r.listItemsNew)
        assertEquals(1, r.listItemsDropped)
        assertEquals(1, r.listsRejected)
        assertTrue(r.rejections.any { it.path.startsWith("lists[1]") })
        assertTrue(r.warnings.any { it.path == "titles[6].viewings[0].outingId" })
        assertTrue(r.warnings.any { it.path == "lists[0].items[1]" })
    }

    @Test fun copyHostileOkTitle() {
        val p = plan(load("hostile.json"))
        val ok = p.titles.single { it.getString("title").startsWith("G") }
        for (k in listOf("userId", "outbox", "receipts", "aliases", "shareToken")) assertFalse("$k kept", ok.has(k))
        val v = ok.getJSONArray("viewings").getJSONObject(0)
        assertFalse(v.has("operationId"))
        assertFalse("dangling outingId", v.has("outingId"))
        assertEquals(ok.getString("id"), v.getString("titleId"))
        assertEquals(6, p.report.untrustedFieldsDropped)
        val text = planText(p)
        for (leaked in listOf("someone-else", "ghost-outing", "ghost-viewing", "\"tok\"")) assertFalse("leaked $leaked", text.contains(leaked))
    }

    @Test fun copyHostileOutings() {
        val p = plan(load("hostile.json"))
        assertTrue(p.outings.isEmpty())
        val missing = p.report.rejections.single { it.path == "outings[1]" }
        assertTrue(missing.message.isNotBlank())
        assertTrue(p.report.rejections.any { it.path.startsWith("outings[0]") && it.message.contains("viewing") })
        assertTrue(p.report.rejections.any { it.path.startsWith("outings[2]") })
    }

    // ── document-level limit ────────────────────────────────────────────────────────────────

    @Test fun docLimitEmptyPlan() {
        val titles = List(Limits.MAX_TITLES + 1) { title("t$it", it + 1) }
        val p = plan(doc(titles))
        assertTrue(p.titles.isEmpty())
        assertTrue(p.outings.isEmpty())
        assertTrue(p.lists.isEmpty())
        assertTrue(p.report.rejections.any { it.fatal && it.path == "\$" })
        assertEquals(0, p.report.titlesNew)
        assertNull(p.report.warnings.firstOrNull { it.fatal })
    }

    @Test
    fun planDropsUnsafeUrls() {
        fun titleJson(id: String, tmdb: Int, extra: String) =
            """{"id":"$id","tmdbId":$tmdb,"type":"movie","title":"T$tmdb","year":2000,"genres":[],"status":"watched","tags":[],"addedAt":"2026-01-01","viewings":[]$extra}"""
        val text = """{"version":2,"titles":[""" +
            titleJson("u1", 1, ""","customWatchUrl":"https://example.com/watch","rtUrl":"https://www.rottentomatoes.com/m/x","posterUrl":"https://image.tmdb.org/t/p/w500/a.jpg"""") + "," +
            titleJson("u2", 2, ""","customWatchUrl":"javascript:alert(1)","rtUrl":"file:///etc/passwd","posterUrl":"data:image/png;base64,AAAA"""") + "," +
            titleJson("u3", 3, ""","customWatchUrl":"https://user:pw@example.com/x","backdropUrl":"content://media/1"""") +
            """],"outings":[]}"""
        val parsed = LibraryBackupCodec.parse(text) as LibraryBackupCodec.ParseResult.Success
        var n = 0
        val plan = LibraryBackupCodec.planCopy(parsed.document, LibraryBackupCodec.ExistingLibrary()) { "n-${++n}" }
        assertEquals(3, plan.titles.size)
        val t1 = plan.titles[0]
        assertEquals("https://example.com/watch", t1.optString("customWatchUrl"))
        assertEquals("https://www.rottentomatoes.com/m/x", t1.optString("rtUrl"))
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", t1.optString("posterUrl"))
        val t2 = plan.titles[1]
        assertFalse(t2.has("customWatchUrl")); assertFalse(t2.has("rtUrl")); assertFalse(t2.has("posterUrl"))
        val t3 = plan.titles[2]
        assertFalse(t3.has("customWatchUrl")); assertFalse(t3.has("backdropUrl"))
        assertEquals(5, plan.report.untrustedFieldsDropped)
        assertTrue(plan.report.warnings.any { it.path.endsWith(".customWatchUrl") })
    }

    @Test
    fun planDropsUnsafeCreditAndStillUrls() {
        val text = """{"version":2,"titles":[{"id":"c1","tmdbId":9,"type":"tv","title":"Cr","year":2000,"genres":[],"status":"watched","tags":[],"addedAt":"2026-01-01","viewings":[],""" +
            """"cast":[{"name":"A","tmdbPersonId":1,"order":0,"profileUrl":"javascript:alert(1)"},{"name":"B","tmdbPersonId":2,"order":1,"profileUrl":"https://image.tmdb.org/t/p/w185/b.jpg"}],""" +
            """"crew":[{"name":"C","tmdbPersonId":3,"job":"Director","profileUrl":"content://x/1"}],""" +
            """"seasons":[{"id":"s1","seasonNumber":1,"episodeCount":1,"episodesWatched":0,"cast":[{"name":"D","tmdbPersonId":4,"order":0,"profileUrl":"data:text/html,x"}],""" +
            """"episodes":[{"id":"e1","episodeNumber":1,"stillUrl":"file:///sdcard/a.jpg","watchEvents":[],"ratings":[],"reviews":[]},{"id":"e2","episodeNumber":2,"stillUrl":"https://image.tmdb.org/t/p/w300/s.jpg","watchEvents":[],"ratings":[],"reviews":[]}]}]}],"outings":[]}"""
        val parsed = LibraryBackupCodec.parse(text) as LibraryBackupCodec.ParseResult.Success
        var n = 0
        val plan = LibraryBackupCodec.planCopy(parsed.document, LibraryBackupCodec.ExistingLibrary()) { "n-${++n}" }
        val t = plan.titles.single()
        val cast = t.getJSONArray("cast")
        assertFalse(cast.getJSONObject(0).has("profileUrl"))
        assertEquals("https://image.tmdb.org/t/p/w185/b.jpg", cast.getJSONObject(1).optString("profileUrl"))
        assertFalse(t.getJSONArray("crew").getJSONObject(0).has("profileUrl"))
        val season = t.getJSONArray("seasons").getJSONObject(0)
        assertFalse(season.getJSONArray("cast").getJSONObject(0).has("profileUrl"))
        val eps = season.getJSONArray("episodes")
        assertFalse(eps.getJSONObject(0).has("stillUrl"))
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", eps.getJSONObject(1).optString("stillUrl"))
        // The archive itself is never mutated: originals stay inert in the document.
        assertEquals("javascript:alert(1)", parsed.document.titles[0].getJSONArray("cast").getJSONObject(0).optString("profileUrl"))
        assertTrue(plan.report.untrustedFieldsDropped >= 4)
    }

    // ── exact numbers + surrogates (review follow-up) ───────────────────────────────────────

    private fun titleWith(extra: String) =
        """{"version":2,"titles":[{"id":"n1","tmdbId":5,"type":"movie","title":"N","year":2000,"genres":[],"status":"watched","tags":[],"addedAt":"2026-01-01","viewings":[]$extra}],"outings":[]}"""

    @Test
    fun exactNumbersSurviveArchiveRoundTrip() {
        val big = "9223372036854775809"
        val dec = "0.12345678901234567890123456789"
        val doc1 = success(titleWith(""","futureBig":$big,"futureDec":$dec,"futureTrail":1.50,"futureExp":1E+3""")).document
        val t = doc1.titles[0]
        assertEquals(java.math.BigInteger(big), t.get("futureBig"))
        assertEquals(java.math.BigDecimal(dec), t.get("futureDec"))
        val encoded = LibraryBackupCodec.encode(doc1, "2026-10-08T00:00:00Z")
        assertTrue(encoded.contains(big))
        assertTrue(encoded.contains(dec))
        assertTrue(encoded.contains("1.50"))
        val doc2 = success(encoded).document
        assertEquals(java.math.BigInteger(big), doc2.titles[0].get("futureBig"))
        assertEquals(java.math.BigDecimal(dec), doc2.titles[0].get("futureDec"))
        assertEquals(0, java.math.BigDecimal("1.50").compareTo(doc2.titles[0].get("futureTrail") as java.math.BigDecimal))
        assertEquals(0, java.math.BigDecimal("1E+3").compareTo(doc2.titles[0].get("futureExp") as java.math.BigDecimal))
    }

    @Test
    fun exactNumbersSurvivePlanExt() {
        val big = "9223372036854775809"
        val dec = "0.12345678901234567890123456789"
        val doc = success(titleWith(""","futureBig":$big,"futureDec":$dec""")).document
        val plan = LibraryBackupCodec.planCopy(doc, ExistingLibrary(), counter())
        val ext = plan.titles.single().getJSONObject("ext")
        assertEquals(java.math.BigInteger(big), ext.get("futureBig"))
        assertEquals(java.math.BigDecimal(dec), ext.get("futureDec"))
    }

    @Test
    fun numberTokenLimitAndTmdbExactness() {
        failure(titleWith(""","futureHuge":${"9".repeat(80)}"""))
        // A beyond-Long or decimal tmdbId is never truncated into range: the row is rejected.
        val d = success("""{"version":2,"titles":[{"id":"x","tmdbId":9223372036854775809,"type":"movie","title":"X","status":"watched"}]}""").document
        assertTrue(fatalPaths(d).isNotEmpty())
    }

    @Test
    fun unpairedSurrogatesRejectedValidPairsKept() {
        failure(titleWith(""","note2":"\ud800""""))
        failure(titleWith(""","note2":"\udc00x""""))
        failure(titleWith(""","note2":"\ud800A""""))
        val pair = success(titleWith(""","note2":"🎬 clapper é"""")).document
        assertEquals("🎬 clapper é", pair.titles[0].getString("note2"))
        val encoded = LibraryBackupCodec.encode(pair, "2026-10-08T00:00:00Z")
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val again = (LibraryBackupCodec.parseBytes(bytes) as ParseResult.Success).document
        assertEquals("🎬 clapper é", again.titles[0].getString("note2"))
        assertFalse(encoded.contains("?"))
        // Raw (unescaped) emoji in the input survives the same way.
        val raw = success(titleWith(",\"note2\":\"🎬\"")).document
        assertEquals("🎬", raw.titles[0].getString("note2"))
    }

    @Test
    fun finiteNumericRangeKeepsExactValues() {
        // Values whose Double view is infinite are refused (framework org.json validates doubleValue()),
        // everything else keeps its exact value — never rounded.
        failure("""{"version":2,"titles":[],"x":1e999}""")
        failure("""{"version":2,"titles":[],"x":-1e999}""")
        failure("""{"version":2,"titles":[],"x":1${"0".repeat(60)}e300}""") // 61-digit mantissa * 1e300 > Double.MAX
        val d = success("""{"version":2,"titles":[],"x":{"a":1e308,"b":1e-999,"c":${"9".repeat(60)},"d":0.1000000000000000055511151231257827}}""").document
        val x = d.extra.getJSONObject("x")
        assertEquals(0, java.math.BigDecimal("1e308").compareTo(x.get("a") as java.math.BigDecimal))
        assertEquals(0, java.math.BigDecimal("1e-999").compareTo(x.get("b") as java.math.BigDecimal))
        assertEquals(java.math.BigInteger("9".repeat(60)), x.get("c"))
        assertEquals(java.math.BigDecimal("0.1000000000000000055511151231257827"), x.get("d"))
    }
}
