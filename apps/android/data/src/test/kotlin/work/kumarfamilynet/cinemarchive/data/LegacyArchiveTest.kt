package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.ListEntity
import work.kumarfamilynet.cinemarchive.core.database.ListItemEntity
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.RoomTransactor
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TheaterInterestEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.VenueNoteEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

@RunWith(RobolectricTestRunner::class)
class LegacyArchiveTest {
    private lateinit var context: Context
    private lateinit var target: LibraryDatabase
    private lateinit var archive: LegacyArchive
    private lateinit var transactor: RoomTransactor

    private val owner = "owner-a"
    private fun claimFile() = File(context.filesDir, "legacy-archive-claim")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(LibraryDatabase.LEGACY_DATABASE_NAME)
        File(context.filesDir, "legacy-archive-claimed").delete()
        claimFile().deleteRecursively()
        File(context.filesDir, "legacy-archive-claim.tmp").delete()
        target = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        transactor = RoomTransactor(target)
        archive = LegacyArchive(context)
    }

    @After
    fun tearDown() {
        target.close()
        context.deleteDatabase(LibraryDatabase.LEGACY_DATABASE_NAME)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun status(db: LibraryDatabase = target, key: String = owner) = archive.inspect(db, key)

    private suspend fun restore(include: Boolean, db: LibraryDatabase = target, tx: RoomTransactor = transactor, key: String = owner) =
        archive.restoreInto(db, tx, key, include)

    private fun exec(db: LibraryDatabase, sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    private fun legacyFile(): File = context.getDatabasePath(LibraryDatabase.LEGACY_DATABASE_NAME)

    private suspend fun createLegacy(block: suspend (LibraryDatabase) -> Unit) {
        val db = LibraryDatabase.create(context, LibraryDatabase.LEGACY_DATABASE_NAME)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun dbDirListing(): List<String> =
        (legacyFile().parentFile?.list() ?: emptyArray()).sorted()

    private fun count(db: LibraryDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun countWhere(db: LibraryDatabase, table: String, where: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table` WHERE $where").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun title(id: String, status: String = "WATCHLIST", synopsis: String? = null, updatedAt: String = "2026-01-01T00:00:00Z") =
        TitleEntity(
            id = id, tmdbId = id.hashCode().and(0xffff), type = "MOVIE", title = "Title $id", year = 2020,
            director = null, genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = synopsis,
            runtime = 100, network = null, status = status, rating = null, notes = null,
            addedAt = "2026-01-01T00:00:00Z", updatedAt = updatedAt,
        )

    private fun outbox(id: String, type: String, entityId: String, op: String, payload: String, createdAt: Long) =
        OutboxEntity(id = id, entityType = type, entityId = entityId, operation = op, payloadJson = payload, createdAt = createdAt)

    private fun outing(id: String, titleId: String, ticketImage: String? = null) = CinemaOutingEntity(
        id = id, titleId = titleId, showtime = "2026-02-01T19:00:00Z", runtimeMinutes = 100,
        endsAt = "2026-02-01T21:00:00Z", venue = "Odeon", format = null, ticketPrice = null,
        ticketImagePath = ticketImage, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
    )

    // ---- inspect -------------------------------------------------------------------------

    @Test
    fun `inspect reports an absent archive`() {
        val status = status()
        assertEquals(LegacyArchiveStatus(present = false, pendingChanges = 0, claimed = false, error = null), status)
    }

    @Test
    fun `inspect is count only and leaves the original and its directory untouched`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1")))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "update", """{"id":"t1"}""", 1))
            db.outboxDao().enqueue(outbox("o2", "title", "t1", "update", """{"id":"t1"}""", 2))
        }
        val hashBefore = sha(legacyFile())
        val listingBefore = dbDirListing()
        val cacheBefore = context.cacheDir.list()?.sorted().orEmpty()

        val status = status()

        assertEquals(LegacyArchiveStatus(present = true, pendingChanges = 2, claimed = false, error = null), status)
        assertEquals(hashBefore, sha(legacyFile()))
        assertEquals(listingBefore, dbDirListing())
        assertEquals(cacheBefore, context.cacheDir.list()?.sorted().orEmpty())
    }

    @Test
    fun `inspect and restore tolerate a corrupt file`() = runTest {
        legacyFile().parentFile!!.mkdirs()
        legacyFile().writeBytes(ByteArray(4096) { 0x41 })
        val hash = sha(legacyFile())

        val status = status()
        assertTrue(status.present)
        assertEquals(0, status.pendingChanges)
        assertNotNull(status.error)

        val result = restore(include = true)
        assertTrue(result is LegacyRestoreResult.Failure)
        assertEquals(hash, sha(legacyFile()))
        assertFalse(status().claimed)
        assertEquals(0, count(target, "mutation_outbox"))
    }

    @Test
    fun `a legacy file with no outbox table reports an error`() {
        legacyFile().parentFile!!.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacyFile(), null)
        raw.execSQL("CREATE TABLE unrelated (x INTEGER)")
        raw.close()

        val status = status()
        assertTrue(status.present)
        assertEquals(0, status.pendingChanges)
        assertNotNull(status.error)
    }

    @Test
    fun `a file too old to migrate fails without being wiped`() = runTest {
        legacyFile().parentFile!!.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(legacyFile(), null)
        raw.execSQL("CREATE TABLE mutation_outbox (id TEXT PRIMARY KEY)")
        raw.execSQL("INSERT INTO mutation_outbox VALUES ('x')")
        raw.version = 3
        raw.close()
        val hash = sha(legacyFile())

        val result = restore(include = true)

        assertTrue(result is LegacyRestoreResult.Failure)
        assertEquals(hash, sha(legacyFile()))
        assertEquals(0, count(target, "mutation_outbox"))
        assertFalse(status().claimed)
    }

    // ---- restore -------------------------------------------------------------------------

    @Test
    fun `restores a pending title insert graph and nothing else`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1"), title("t2")))
            db.seasonDao().upsertAll(listOf(SeasonEntity("s1", "t1", 1, 1, 0, 2020)))
            db.episodeDao().upsertAll(listOf(EpisodeEntity("e1", "t1", "s1", 1, "Pilot", null, null)))
            db.episodeWatchEventDao().upsertAll(listOf(EpisodeWatchEventEntity("w1", "e1", null)))
            db.viewingDao().upsert(ViewingEntity("v1", "t1", "2026-01-02", 4.0, null, null))
            db.titleCastDao().upsertAll(listOf(TitleCastEntity("c1", "t1", 1, "Actor", null, 0)))
            db.titleCrewDao().upsertAll(listOf(TitleCrewEntity("k1", "t1", 2, "Dir", "Director", null)))
            // t2 is a plain synced title with no pending entry: must NOT be copied.
            db.viewingDao().upsert(ViewingEntity("v2", "t2", "2026-01-03", null, null, null))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "insert", """{"id":"t1"}""", 10))
        }
        val hash = sha(legacyFile())

        val result = restore(include = false)

        assertEquals(LegacyRestoreResult.Success(restoredOutboxEntries = 1, restoredRows = 7, skippedRows = 0), result)
        assertNotNull(target.titleDao().getById("t1"))
        assertNull(target.titleDao().getById("t2"))
        assertEquals(1, count(target, "seasons"))
        assertEquals(1, count(target, "episodes"))
        assertEquals(1, count(target, "episode_watch_events"))
        assertEquals(1, count(target, "viewings"))
        assertEquals(1, count(target, "title_cast"))
        assertEquals(1, count(target, "title_crew"))
        assertEquals(listOf("o1"), target.outboxDao().getPending().map { it.id })
        assertEquals(hash, sha(legacyFile()))
        assertTrue(legacyFile().exists())
        // t2/v2 are local-only candidates that were deliberately not included, so the archive is NOT
        // marked complete/claimed (the user can still recover them) …
        assertFalse(status().claimed)

        // … but a repeat restore never replays the already-restored queue entry.
        val again = restore(include = false)
        assertEquals(LegacyRestoreResult.Success(0, 0, 0), again)
        assertEquals(1, count(target, "mutation_outbox"))
        // No working copies are left behind.
        assertTrue(context.cacheDir.list().orEmpty().none { it.startsWith("legacy-") })
    }

    @Test
    fun `a pending status edit overrides the synced title but keeps other target fields`() = runTest {
        target.titleDao().upsertAll(listOf(title("t1", status = "WATCHLIST", synopsis = "server synopsis")))
        target.viewingDao().upsert(ViewingEntity("v1", "t1", "2026-01-02", null, null, null))
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1", status = "WATCHED", synopsis = "stale", updatedAt = "2026-05-05T00:00:00Z")))
            db.outboxDao().enqueue(
                outbox("o1", "title", "t1", "update", """{"id":"t1","status":"WATCHED","updatedAt":"2026-05-05T00:00:00Z"}""", 5),
            )
        }

        val result = restore(include = true)

        assertEquals(LegacyRestoreResult.Success(1, 1, 0), result)
        val row = target.titleDao().getById("t1")!!
        assertEquals("WATCHED", row.status)
        assertEquals("2026-05-05T00:00:00Z", row.updatedAt)
        assertEquals("server synopsis", row.synopsis)
        // REPLACE-style delete+insert must not have cascaded away the title's children.
        assertEquals(1, count(target, "viewings"))
        assertEquals(1, count(target, "mutation_outbox"))
    }

    @Test
    fun `a pending delete removes the row from the target and copies the entry`() = runTest {
        target.titleDao().upsertAll(listOf(title("t1")))
        target.viewingDao().upsert(ViewingEntity("v1", "t1", "2026-01-02", null, null, null))
        createLegacy { db ->
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "delete", """{"id":"t1"}""", 5))
        }

        val result = restore(include = true)

        assertEquals(LegacyRestoreResult.Success(1, 1, 0), result)
        assertNull(target.titleDao().getById("t1"))
        assertEquals(0, count(target, "viewings"))
        assertEquals(listOf("o1"), target.outboxDao().getPending().map { it.id })
    }

    @Test
    fun `local only tables are copied when absent and ticket fields fill gaps`() = runTest {
        target.titleDao().upsertAll(listOf(title("t1")))
        target.cinemaOutingDao().upsert(outing("out1", "t1"))
        target.venueNoteDao().upsert(VenueNoteEntity("Odeon", "target note", "2026-01-01"))
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1")))
            db.cinemaOutingDao().upsert(outing("out1", "t1", ticketImage = "/files/ticket.jpg"))
            db.cinemaOutingDao().upsert(outing("out2", "t1")) // only in legacy, no pending entry: local-only
            db.venueNoteDao().upsert(VenueNoteEntity("Odeon", "legacy note", "2026-02-01"))
            db.venueNoteDao().upsert(VenueNoteEntity("Vue", "park opposite", "2026-02-01"))
            db.theaterInterestDao().upsert(TheaterInterestEntity("t9", "2026-02-01"))
        }

        val result = restore(include = true)

        // Vue note + theater interest + out2 (local-only) + out1 ticket fields; nothing enqueued
        assertEquals(LegacyRestoreResult.Success(0, 4, 0), result)
        assertEquals(0, count(target, "mutation_outbox"))
        val notes = target.openHelper.readableDatabase.query("SELECT venue, notes FROM venue_notes ORDER BY venue").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        assertEquals(listOf("Odeon" to "target note", "Vue" to "park opposite"), notes)
        assertEquals(1, count(target, "theater_interest"))
        assertEquals("/files/ticket.jpg", target.cinemaOutingDao().getById("out1")!!.ticketImagePath)
        assertNotNull(target.cinemaOutingDao().getById("out2"))
    }

    @Test
    fun `orphans and unknown entries are skipped without crashing`() = runTest {
        createLegacy { db ->
            val raw = db.openHelper.writableDatabase
            raw.execSQL("PRAGMA foreign_keys = OFF")
            raw.execSQL(
                "INSERT INTO viewings (id, titleId, date, rating, notes, venue, companions, outingId) " +
                    "VALUES ('v-orphan', 'ghost', '2026-01-01', NULL, NULL, NULL, '', NULL)",
            )
            raw.execSQL("PRAGMA foreign_keys = ON")
            db.outboxDao().enqueue(outbox("o1", "viewing", "v-orphan", "upsert", """{"id":"v-orphan"}""", 1))
            db.outboxDao().enqueue(outbox("o2", "viewing", "v-missing", "upsert", """{"id":"v-missing"}""", 2))
            db.outboxDao().enqueue(outbox("o3", "mystery", "m1", "upsert", "{}", 3))
        }

        val result = restore(include = true)

        assertEquals(LegacyRestoreResult.Success(restoredOutboxEntries = 3, restoredRows = 0, skippedRows = 3), result)
        assertEquals(0, count(target, "viewings"))
        assertEquals(3, count(target, "mutation_outbox"))
        // Skipped rows: no complete receipt, the archive stays available to this owner.
        val st = status()
        assertFalse(st.claimed)
        assertEquals(3, st.skippedRows)
        assertEquals(0, countWhere(target, "legacy_restore_receipt", "kind = 'complete'"))
        assertTrue(claimFile().readText().contains("state=pending"))
    }

    @Test
    fun `a failed restore rolls back and leaves the marker unwritten`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1")))
            db.venueNoteDao().upsert(VenueNoteEntity("Vue", "n", "2026-01-01"))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "insert", """{"id":"t1"}""", 1))
        }
        val hash = sha(legacyFile())
        archive.beforeCommitHook = { error("boom") }

        val failed = restore(include = true)

        assertTrue(failed is LegacyRestoreResult.Failure)
        assertEquals(0, count(target, "titles"))
        assertEquals(0, count(target, "mutation_outbox"))
        assertEquals(0, count(target, "venue_notes"))
        assertEquals(0, count(target, "legacy_restore_receipt"))
        assertFalse(claimFile().exists())
        assertFalse(status().claimed)
        assertEquals(hash, sha(legacyFile()))

        archive.beforeCommitHook = null
        val retried = restore(include = true)
        assertTrue(retried is LegacyRestoreResult.Success)
        assertEquals(1, count(target, "titles"))
        assertTrue(status().claimed)
    }

    @Test
    fun `ticket file stays reachable and identical`() = runTest {
        val ticketsDir = File(context.filesDir, "tickets").apply { mkdirs() }
        val ticket = File(ticketsDir, "legacy-ticket.jpg").also { it.writeBytes(ByteArray(2048) { i -> (i * 7).toByte() }) }
        val bytesBefore = ticket.readBytes()
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1")))
            db.cinemaOutingDao().upsert(outing("out1", "t1", ticketImage = ticket.absolutePath))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "insert", """{"id":"t1"}""", 1))
            db.outboxDao().enqueue(outbox("o2", "cinema_outing", "out1", "upsert", """{"id":"out1","titleId":"t1"}""", 2))
        }
        val otherAccount = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val result = restore(include = true)
            assertTrue(result.toString(), result is LegacyRestoreResult.Success)

            val restored = target.cinemaOutingDao().getById("out1")!!
            assertEquals("path restored as-is (same private file, no copy)", ticket.absolutePath, restored.ticketImagePath)
            assertTrue(File(restored.ticketImagePath!!).exists())
            assertTrue("bytes untouched", bytesBefore.contentEquals(File(restored.ticketImagePath!!).readBytes()))
            assertNull("another account's database never references the file", otherAccount.cinemaOutingDao().getById("out1"))
            assertEquals(0, count(otherAccount, "cinema_outings"))
        } finally {
            otherAccount.close()
            ticket.delete()
        }
    }

    // ---- idempotence / claim -------------------------------------------------------------

    private suspend fun simpleLegacy() = createLegacy { db ->
        db.titleDao().upsertAll(listOf(title("t1")))
        db.outboxDao().enqueue(outbox("o1", "title", "t1", "insert", """{"id":"t1"}""", 1))
    }

    @Test
    fun `crash after commit is repaired and never requeues`() = runTest {
        simpleLegacy()
        archive.afterCommitHook = { error("crash") }

        val crashed = restore(include = true)

        assertTrue(crashed is LegacyRestoreResult.Failure)
        assertEquals(listOf("o1"), target.outboxDao().getPending().map { it.id })
        assertEquals(2, count(target, "legacy_restore_receipt")) // entry:o1 + archive:<id>
        assertTrue(claimFile().readText().contains("state=pending"))

        // o1 is pushed and removed from the outbox meanwhile.
        exec(target, "DELETE FROM mutation_outbox")
        archive.afterCommitHook = null

        val st = status()
        assertTrue(st.claimed)
        assertFalse(st.claimedByOther)
        assertTrue(claimFile().readText().contains("state=done"))
        assertTrue(restore(include = true) is LegacyRestoreResult.Failure)
        assertEquals(0, count(target, "mutation_outbox"))
    }

    @Test
    fun `claim write failure changes nothing`() = runTest {
        simpleLegacy()
        assertTrue(claimFile().mkdirs())

        val result = restore(include = true)

        assertTrue(result is LegacyRestoreResult.Failure)
        assertEquals(0, count(target, "titles"))
        assertEquals(0, count(target, "mutation_outbox"))
        assertEquals(0, count(target, "legacy_restore_receipt"))
    }

    @Test
    fun `a delivered entry is not re-added on retry`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1"), title("t2")))
            db.viewingDao().upsert(ViewingEntity("v2", "t2", "2026-01-03", null, null, null))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "insert", """{"id":"t1"}""", 1))
        }
        assertTrue(restore(include = false) is LegacyRestoreResult.Success) // t2/v2 left: not complete
        exec(target, "DELETE FROM mutation_outbox") // pushed

        val again = restore(include = false)

        assertEquals(LegacyRestoreResult.Success(0, 0, 0), again)
        assertEquals(0, count(target, "mutation_outbox"))
    }

    @Test
    fun `second account is blocked by the first claim`() = runTest {
        simpleLegacy()
        assertTrue(restore(include = true) is LegacyRestoreResult.Success)
        val other = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val result = restore(include = true, db = other, tx = RoomTransactor(other), key = "owner-b")

            assertTrue(result is LegacyRestoreResult.Failure)
            assertTrue((result as LegacyRestoreResult.Failure).message.contains("another account"))
            assertEquals(0, count(other, "mutation_outbox"))
            assertEquals(0, count(other, "legacy_restore_receipt"))
            val st = status(other, "owner-b")
            assertTrue(st.claimed)
            assertTrue(st.claimedByOther)
        } finally {
            other.close()
        }
    }

    @Test
    fun `pending claim blocks others but the same owner retries`() = runTest {
        simpleLegacy()
        claimFile().writeText("owner=$owner\nstate=pending\n") // crashed before commit
        assertFalse(status().claimed)
        val other = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            assertTrue(restore(include = true, db = other, tx = RoomTransactor(other), key = "owner-b") is LegacyRestoreResult.Failure)
            assertEquals(0, count(other, "mutation_outbox"))
        } finally {
            other.close()
        }

        assertTrue(restore(include = true) is LegacyRestoreResult.Success)
        assertEquals(1, count(target, "mutation_outbox"))
        assertTrue(claimFile().readText().contains("state=done"))
    }

    @Test
    fun `the old marker file blocks every account`() = runTest {
        simpleLegacy()
        File(context.filesDir, "legacy-archive-claimed").writeText("claimed")
        try {
            assertTrue(status().claimed)
            assertTrue(restore(include = true) is LegacyRestoreResult.Failure)
            assertEquals(0, count(target, "mutation_outbox"))
        } finally {
            File(context.filesDir, "legacy-archive-claimed").delete()
        }
    }

    // ---- local-only items ----------------------------------------------------------------

    @Test
    fun `local only count skips present and covered rows`() = runTest {
        target.titleDao().upsertAll(listOf(title("t3")))
        target.viewingDao().upsert(ViewingEntity("vb", "t3", "2026-01-02", null, null, null))
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t1"), title("t2"), title("t3")))
            db.viewingDao().upsert(ViewingEntity("va", "t2", "2026-01-02", null, null, null))
            db.viewingDao().upsert(ViewingEntity("vb", "t3", "2026-01-02", null, null, null))
            db.venueNoteDao().upsert(VenueNoteEntity("Vue", "n", "2026-01-01"))
            db.theaterInterestDao().upsert(TheaterInterestEntity("t9", "2026-01-01"))
            db.outboxDao().enqueue(outbox("o1", "title", "t1", "update", """{"id":"t1"}""", 1))
        }

        val withTarget = status()
        assertEquals(1, withTarget.pendingChanges)
        assertEquals(4, withTarget.localOnlyItems) // t2, va, Vue note, t9 interest
        val legacyOnly = archive.inspect()
        assertEquals(1, legacyOnly.pendingChanges)
        assertEquals(2, legacyOnly.localOnlyItems) // venue note + theater interest only
    }

    @Test
    fun `local only restore stays local and respects parents`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t2")))
            db.viewingDao().upsert(ViewingEntity("va", "t2", "2026-01-02", null, null, null))
            db.listDao().upsert(ListEntity("L1", "Faves", null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))
            db.listItemDao().upsert(ListItemEntity("li1", "L1", "t2", 1, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"))
        }
        val before = status()
        assertEquals(0, before.pendingChanges)
        assertEquals(4, before.localOnlyItems)

        val result = restore(include = true)

        assertEquals(LegacyRestoreResult.Success(0, 4, 0), result)
        assertEquals(0, count(target, "mutation_outbox"))
        assertNotNull(target.titleDao().getById("t2"))
        assertEquals(1, count(target, "viewings"))
        assertEquals(1, count(target, "lists"))
        assertEquals(1, count(target, "list_items"))
        assertTrue(status().claimed)
    }

    @Test
    fun `an unrestorable local row keeps the archive available`() = runTest {
        createLegacy { db ->
            val raw = db.openHelper.writableDatabase
            raw.execSQL("PRAGMA foreign_keys = OFF")
            raw.execSQL(
                "INSERT INTO viewings (id, titleId, date, rating, notes, venue, companions, outingId) " +
                    "VALUES ('v-orphan', 'ghost', '2026-01-01', NULL, NULL, NULL, '', NULL)",
            )
            raw.execSQL("PRAGMA foreign_keys = ON")
        }

        val result = restore(include = true)

        assertEquals(LegacyRestoreResult.Success(0, 0, 1), result)
        assertEquals(0, count(target, "viewings"))
        assertEquals(0, countWhere(target, "legacy_restore_receipt", "kind = 'complete'"))
        val st = status()
        assertFalse(st.claimed)
        assertEquals(1, st.localOnlyItems)
    }

    @Test
    fun `without local only the archive is not complete`() = runTest {
        createLegacy { db ->
            db.titleDao().upsertAll(listOf(title("t2")))
        }

        assertEquals(LegacyRestoreResult.Success(0, 0, 0), restore(include = false))
        assertEquals(0, count(target, "titles"))
        assertFalse(status().claimed)

        assertTrue(restore(include = true) is LegacyRestoreResult.Success)
        assertEquals(1, count(target, "titles"))
        assertTrue(status().claimed)
    }
}
