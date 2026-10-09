package work.kumarfamilynet.cinemarchive.data

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.data.BackupRestoreMapper.Kind

class BackupRestoreMapperTest {
    private val at = "2026-10-09T00:30:00Z"
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun fixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/fixtures/library-backup/expected-restore-title.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        fail("expected-restore-title.json not found")
        throw IllegalStateException()
    }

    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is Number -> BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun entityJson(e: TitleEntity): JSONObject = JSONObject().apply {
        put("id", e.id).put("tmdbId", e.tmdbId).put("type", e.type).put("title", e.title).put("status", e.status)
        put("addedAt", e.addedAt).put("updatedAt", e.updatedAt).put("genres", JSONArray(e.genres)).put("tags", JSONArray(e.tags))
        if (e.studios.isNotEmpty()) put("studios", JSONArray(e.studios))
        e.physicalMediaJson?.let { put("physicalMedia", JSONArray(it)) }
        val optional = mapOf<String, Any?>(
            "year" to e.year, "director" to e.director, "posterUrl" to e.posterUrl, "backdropUrl" to e.backdropUrl,
            "synopsis" to e.synopsis, "runtime" to e.runtime, "network" to e.network, "rating" to e.rating,
            "notes" to e.notes, "imdbRating" to e.imdbRating, "originalLanguage" to e.originalLanguage,
            "releaseDate" to e.releaseDate, "collectionId" to e.collectionId, "collectionName" to e.collectionName,
            "contentRating" to e.contentRating, "imdbId" to e.imdbId, "rtUrl" to e.rtUrl, "rtScore" to e.rtScore,
            "metacriticScore" to e.metacriticScore, "customWatchUrl" to e.customWatchUrl,
            "inHomeCollection" to e.inHomeCollection, "awardsCount" to e.awardsCount,
            "bechdelOutcome" to e.bechdelOutcome, "bechdelScore" to e.bechdelScore,
        )
        for ((k, v) in optional) put(k, v ?: JSONObject.NULL)
    }

    /** Fixture entity JSON lists non-null fields only (plus `director: null` once); drop nulls on both sides. */
    private fun withoutNulls(o: JSONObject) = JSONObject().also { r -> o.keys().forEach { if (!o.isNull(it)) r.put(it, o.get(it)) } }

    private fun base(): JSONObject = JSONObject()
        .put("id", id).put("tmdbId", 603).put("type", "movie").put("title", "The Matrix")
        .put("year", 1999).put("status", "watched")

    private fun map(row: JSONObject) = BackupRestoreMapper.mapTitle(row, 0, at)
    private fun values(m: BackupRestoreMapper.TitleMapping) = m.operation!!.getJSONObject("values")
    private fun paths(m: BackupRestoreMapper.TitleMapping, kind: Kind) = m.issues.filter { it.kind == kind }.map { it.path }

    @Test fun goldenFixture() {
        val fx = JSONObject(fixtureFile().readText())
        val m = BackupRestoreMapper.mapTitle(fx.getJSONObject("input"), 0, fx.getString("admittedAt"))
        assertEquals(canon(fx.getJSONObject("expectedOperation")), canon(m.operation))
        assertEquals(canon(withoutNulls(fx.getJSONObject("expectedEntity"))), canon(withoutNulls(entityJson(m.entity!!))))
        val expected = fx.getJSONArray("expectedIssues")
        val actual = m.issues.map { it.path to it.kind.name }
        assertEquals((0 until expected.length()).map { expected.getJSONObject(it).let { o -> o.getString("path") to o.getString("kind") } }, actual)
    }

    @Test fun sameFrozenInputsGiveIdenticalBytes() {
        val fx = JSONObject(fixtureFile().readText())
        val a = BackupRestoreMapper.mapTitle(JSONObject(fx.getJSONObject("input").toString()), 0, at)
        val b = BackupRestoreMapper.mapTitle(JSONObject(fx.getJSONObject("input").toString()), 0, at)
        assertEquals(a.operationJson, b.operationJson)
        assertEquals(a.entity, b.entity)
        assertEquals(a.issues, b.issues)
    }

    @Test fun inputIsNotMutated() {
        val input = JSONObject(JSONObject(fixtureFile().readText()).getJSONObject("input").toString())
        val before = canon(input)
        map(input).operation!!.getJSONObject("values").getJSONArray("physical_media").getJSONObject(0).put("format", "changed")
        assertEquals(before, canon(input))
    }

    @Test fun nullFalseZeroEmptyArePreservedNotDropped() {
        val m = map(base().put("rtScore", 0).put("metacriticScore", 0).put("awardsCount", 0).put("runtime", 0)
            .put("rating", 0).put("notes", "").put("synopsis", "").put("inHomeCollection", false)
            .put("genres", JSONArray()).put("tags", JSONArray()).put("director", JSONObject.NULL).put("addedAt", at))
        val v = values(m)
        for (k in listOf("rt_score", "metacritic_score", "awards_count", "runtime")) assertEquals(k, 0, v.get(k))
        assertEquals(0, BigDecimal(v.get("rating").toString()).compareTo(BigDecimal.ZERO))
        assertEquals("", v.getString("notes"))
        assertEquals("", v.getString("synopsis"))
        assertFalse(v.getBoolean("in_home_collection"))
        assertEquals(0, v.getJSONArray("genres").length())
        assertFalse(v.has("director"))
        val e = m.entity!!
        assertEquals(0, e.rtScore); assertEquals(0, e.runtime); assertEquals(0.0, e.rating!!, 0.0)
        assertEquals("", e.notes); assertNull(e.director); assertEquals(false, e.inHomeCollection)
        assertTrue(m.issues.none { it.kind == Kind.LOSSY || it.kind == Kind.REJECTED })
    }

    @Test fun absentInHomeCollectionMatchesServerDefaultFalse() {
        val m = map(base())
        assertEquals(false, m.entity!!.inHomeCollection)
        assertFalse(values(m).has("in_home_collection"))
        assertNull(m.entity!!.physicalMediaJson)
        assertFalse(values(m).has("physical_media"))
    }

    @Test fun physicalMediaKeepsEveryRawField() {
        val raw = """[{"id":"a","format":"BD","extra":{"n":[1,2]}},"odd",7]"""
        val m = map(base().put("physicalMedia", JSONArray(raw)))
        assertEquals(canon(JSONArray(raw)), canon(values(m).getJSONArray("physical_media")))
        assertEquals(canon(JSONArray(raw)), canon(JSONArray(m.entity!!.physicalMediaJson)))
        assertEquals(listOf("titles[0].physicalMedia[1]", "titles[0].physicalMedia[2]"), paths(m, Kind.UNMAPPED))
    }

    @Test fun operationShapeAndAuthorityExclusion() {
        val row = base().put("user_id", "x").put("userId", "x").put("ticketAttachment", JSONObject()).put("operationId", "op")
            .put("shareToken", "t").put("updated_at", "2000-01-01T00:00:00Z")
        val m = map(row)
        val op = m.operation!!
        assertEquals(setOf("table", "action", "key", "values"), op.keys().asSequence().toSet())
        assertEquals("titles", op.getString("table")); assertEquals("insert", op.getString("action"))
        assertEquals(setOf("id"), op.getJSONObject("key").keys().asSequence().toSet())
        val allowed = setOf("tmdb_id", "type", "title", "year", "director", "genres", "poster_url", "backdrop_url", "synopsis",
            "runtime", "network", "status", "rating", "notes", "tags", "imdb_rating", "rt_score", "metacritic_score", "studios",
            "added_at", "release_date", "original_language", "content_rating", "imdb_id", "rt_url", "awards_count",
            "bechdel_outcome", "bechdel_score", "custom_watch_url", "in_home_collection", "physical_media", "collection_id", "collection_name")
        assertTrue(allowed.containsAll(values(m).keys().asSequence().toSet()))
        assertEquals(setOf("user_id", "userId", "ticketAttachment", "operationId", "shareToken", "updated_at").map { "titles[0].$it" }.toSet(), paths(m, Kind.UNMAPPED).toSet())
        for (forbidden in listOf("user_id", "userId", "updated_at", "ticket", "token", "operation")) assertFalse(op.toString().contains("\"$forbidden"))
    }

    @Test fun enumsAndTimestampsFollowEachSide() {
        val m = map(base().put("type", "tv").put("status", "watchlist").put("addedAt", "2020-01-02T03:04:05.678+01:00"))
        assertEquals("tv", values(m).getString("type")); assertEquals("TV", m.entity!!.type)
        assertEquals("watchlist", values(m).getString("status")); assertEquals("WATCHLIST", m.entity!!.status)
        assertEquals("2020-01-02T03:04:05.678+01:00", values(m).getString("added_at"))
        assertEquals("2020-01-02T03:04:05.678+01:00", m.entity!!.addedAt)
        assertEquals(at, m.entity!!.updatedAt)
        assertFalse(values(m).has("updated_at"))
    }

    @Test fun missingOrDateOnlyAddedAtIsReportedAndUsesAdmissionTime() {
        for (row in listOf(base(), base().put("addedAt", "2020-01-02"), base().put("addedAt", 5))) {
            val m = map(row)
            assertEquals(at, m.entity!!.addedAt); assertEquals(at, values(m).getString("added_at"))
            assertEquals(listOf("titles[0].addedAt"), paths(m, Kind.LOSSY))
        }
    }

    @Test fun invalidRichFieldsAreLeftOutAndReportedNeverCoerced() {
        val m = map(base().put("rating", 4.25).put("imdbRating", "8").put("runtime", 1.5).put("rtScore", 101)
            .put("metacriticScore", "7").put("awardsCount", -1).put("genres", JSONArray().put("a").put(1)).put("tags", "x")
            .put("studios", JSONObject()).put("releaseDate", "1999-03-31T00:00:00Z").put("bechdelOutcome", "maybe")
            .put("inHomeCollection", "true").put("physicalMedia", JSONObject()).put("director", 5).put("notes", "a\u0000b")
            .put("posterUrl", false).put("collectionId", 1.0).put("collectionName", JSONArray()))
        val v = values(m)
        for (k in listOf("rating", "imdb_rating", "runtime", "rt_score", "metacritic_score", "awards_count", "release_date",
            "bechdel_outcome", "in_home_collection", "physical_media", "director", "notes", "poster_url", "collection_id", "collection_name")) {
            assertFalse(k, v.has(k))
        }
        assertEquals(setOf("tmdb_id", "type", "title", "year", "status", "added_at"), v.keys().asSequence().toSet())
        assertEquals(19, paths(m, Kind.LOSSY).size)
    }

    @Test fun invalidRichFieldsListEachPath() {
        val m = map(base().put("rating", 4.25).put("runtime", 1.5).put("genres", JSONArray().put(1)).put("inHomeCollection", "yes"))
        assertEquals(setOf("titles[0].rating", "titles[0].runtime", "titles[0].genres", "titles[0].inHomeCollection", "titles[0].addedAt"),
            paths(m, Kind.LOSSY).toSet())
        assertEquals(false, m.entity!!.inHomeCollection)
        assertNull(m.entity!!.rating); assertNull(m.entity!!.runtime); assertEquals(emptyList<String>(), m.entity!!.genres)
    }

    @Test fun ratingBoundsAndPrecision() {
        for (ok in listOf<Any>(0, 5, 4.5, BigDecimal("3.0"), BigInteger.valueOf(2))) {
            val m = map(base().put("rating", ok))
            assertNotNull(ok.toString(), m.entity!!.rating)
        }
        for (bad in listOf<Any>(5.1, -0.5, 4.55, BigDecimal("1E+1"))) {
            val m = map(base().put("rating", bad))
            assertNull(bad.toString(), m.entity!!.rating)
            assertTrue(bad.toString(), "titles[0].rating" in paths(m, Kind.LOSSY))
        }
    }

    @Test fun requiredFieldsRejectTheTitleWithoutInventingValues() {
        val cases = mapOf(
            "id" to base().put("id", "not-a-uuid"),
            "tmdbId" to base().put("tmdbId", 0),
            "type" to base().put("type", "MOVIE"),
            "status" to base().put("status", "unknown"),
            "title" to base().put("title", " "),
            "year" to base().also { it.remove("year") },
        )
        for ((field, row) in cases) {
            val m = map(row)
            assertNull(field, m.entity); assertNull(field, m.operation)
            assertEquals(field, listOf("titles[0].$field"), paths(m, Kind.REJECTED))
        }
        assertNull(map(base().put("year", 1999.0)).operation)
    }

    @Test fun deferredAndOpaqueFieldsAreReportedByPath() {
        val m = map(base().put("seasons", JSONArray().put(JSONObject())).put("viewings", JSONArray().put(JSONObject()).put(JSONObject()))
            .put("cast", JSONArray().put(JSONObject())).put("crew", JSONArray()).put("ext", JSONObject().put("a", 1)).put("mystery", 1))
        assertEquals(listOf("titles[0].cast", "titles[0].seasons", "titles[0].viewings"), paths(m, Kind.DEFERRED))
        assertEquals(listOf("titles[0].ext", "titles[0].mystery"), paths(m, Kind.UNMAPPED))
        assertTrue(m.issues.first { it.path == "titles[0].viewings" }.message.startsWith("2 item"))
        val text = values(m).toString()
        assertFalse(text.contains("mystery")); assertFalse(text.contains("viewings"))
    }

    @Test fun planMappingReportsDeferredOutingsAndLists() {
        val doc = (LibraryBackupCodec.parse(
            """{"version":1,"exportedAt":"2024-01-01","titles":[{"id":"t1","tmdbId":1,"type":"movie","title":"A","year":2000,"status":"watched"}],""" +
                """"outings":[{"id":"o1","titleId":"t1","showtime":"2024-01-01T10:00:00Z","endsAt":"2024-01-01T12:00:00Z","status":"scheduled"}]}""",
        ) as LibraryBackupCodec.ParseResult.Success).document
        var n = 0
        val plan = LibraryBackupCodec.planCopy(doc, LibraryBackupCodec.ExistingLibrary(emptyMap())) {
            "00000000-0000-4000-8000-" + (++n).toString().padStart(12, '0')
        }
        doc.extra.put("future", JSONObject().put("value", 1))
        assertEquals(1, plan.outings.size)
        assertEquals(0, plan.report.outingsRejected)
        val mapped = BackupRestoreMapper.mapPlan(doc, plan, at)
        assertEquals(1, mapped.titles.size)
        assertEquals(plan.titles[0].getString("id"), mapped.titles[0].entity!!.id)
        assertEquals(listOf("outings", "future"), mapped.issues.map { it.path })
        assertEquals(Kind.DEFERRED, mapped.issues[0].kind)
        assertTrue(mapped.copyReport === plan.report)
    }

    @Test fun yearMatchesServerIntegerDomainIncludingUnknownZero() {
        for (year in listOf(0, -1, 10000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val mapped = map(base().put("year", year).put("addedAt", at))
            assertEquals(year, mapped.entity!!.year)
            assertEquals(year, values(mapped).getInt("year"))
            assertTrue(mapped.issues.isEmpty())
        }
        assertNull(map(base().put("year", BigInteger("2147483648"))).operationJson)
    }

    @Test fun exactOpaqueNumbersAndFrozenOperationSurviveDetachedMutations() {
        val big = BigInteger("9223372036854775809")
        val decimal = BigDecimal("0.12345678901234567890123456789")
        val nested = JSONObject().put("large", big).put("decimal", decimal)
        val row = base().put("physicalMedia", JSONArray().put(JSONObject().put("id", "copy").put("future", nested)))
        val mapped = map(row)
        val bytes = mapped.operationJson!!
        assertTrue(bytes.contains(big.toString())); assertTrue(bytes.contains(decimal.toString()))
        assertTrue(mapped.entity!!.physicalMediaJson!!.contains(decimal.toString()))
        values(mapped).getJSONArray("physical_media").getJSONObject(0).getJSONObject("future").put("large", 0)
        assertEquals(big, nested.get("large"))
        nested.put("decimal", 0)
        assertEquals(decimal, values(mapped).getJSONArray("physical_media").getJSONObject(0).getJSONObject("future").get("decimal"))
        assertEquals(bytes, mapped.operationJson)
    }

    @Test fun planMappingReportsPrivateAndExtraPathsWithoutLosingAdmissionReport() {
        val doc = (LibraryBackupCodec.parse("""{"titles":[],"localOnly":{"venueNotes":[1],"theaterInterest":false},"future":0}""") as LibraryBackupCodec.ParseResult.Success).document
        val plan = LibraryBackupCodec.planCopy(doc, LibraryBackupCodec.ExistingLibrary())
        val mapped = BackupRestoreMapper.mapPlan(doc, plan, at)
        assertEquals(listOf("localOnly.theaterInterest", "localOnly.venueNotes", "future"), mapped.issues.map { it.path })
        assertTrue(mapped.issues.all { it.kind == Kind.UNMAPPED })
        assertTrue(mapped.copyReport === plan.report)
        assertEquals(1, mapped.copyReport.unknownTopLevelKeysIgnored)
        assertEquals(1, doc.localOnly!!.getJSONArray("venueNotes").getInt(0))
    }

    @Test fun admittedAtMustBeAFrozenInstant() {
        try { BackupRestoreMapper.mapTitle(base(), 0, "2026-10-09"); fail("date-only admittedAt must be refused") }
        catch (_: IllegalArgumentException) { }
    }
}
