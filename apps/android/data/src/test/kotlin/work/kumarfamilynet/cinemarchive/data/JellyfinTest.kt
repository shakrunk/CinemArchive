package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

class JellyfinTest {
    private val since = "2026-10-24T00:00:00.000Z"
    private val series = mapOf("s1" to ExternalIds(tmdb = 95396, tvdb = 371980))

    private fun json(raw: String) = JSONArray(raw)

    // ─── mapJellyfinPlays ────────────────────────────────────────────────────

    @Test fun `keeps only plays after the cursor, oldest first, with ids`() {
        val out = mapJellyfinPlays(
            json(
                """[
                {"Id":"m1","Type":"Movie","Name":"Heat","ProductionYear":1995,"ProviderIds":{"Tmdb":"949","Imdb":"tt0113277"},"UserData":{"Played":true,"LastPlayedDate":"2026-10-24T20:00:00Z"}},
                {"Id":"m2","Type":"Movie","Name":"Old","UserData":{"Played":true,"LastPlayedDate":"2026-10-01T20:00:00Z"}},
                {"Id":"m3","Type":"Movie","Name":"Unplayed","UserData":{"Played":false}},
                {"Id":"e1","Type":"Episode","Name":"Hello","SeriesId":"s1","SeriesName":"Severance","ParentIndexNumber":1,"IndexNumber":1,"UserData":{"Played":true,"LastPlayedDate":"2026-10-24T10:00:00Z"}}
                ]""",
            ),
            series, since,
        )
        assertEquals(listOf("Severance", "Heat"), out.map { it.title })
        val episode = out[0]
        assertEquals(JellyfinPlay.Kind.EPISODE, episode.kind)
        assertEquals(1, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
        assertEquals("2026-10-24", episode.date)
        assertEquals(95396, episode.ids.tmdb)
        val movie = out[1]
        assertEquals(JellyfinPlay.Kind.MOVIE, movie.kind)
        assertEquals("m1@2026-10-24T20:00:00Z", movie.key)
        assertEquals(ExternalIds(tmdb = 949, imdb = "tt0113277"), movie.ids)
        assertEquals(1995, movie.year)
    }

    @Test fun `skips episodes without season or episode numbers and a play exactly at the cursor`() {
        val out = mapJellyfinPlays(
            json(
                """[
                {"Id":"e2","Type":"Episode","Name":"Loose","UserData":{"Played":true,"LastPlayedDate":"2026-10-25T10:00:00Z"}},
                {"Id":"m4","Type":"Movie","Name":"Edge","UserData":{"Played":true,"LastPlayedDate":"2026-10-24T00:00:00.000Z"}}
                ]""",
            ),
            series, since,
        )
        assertEquals(emptyList<JellyfinPlay>(), out)
    }

    @Test fun `accepts lower-case provider id keys and 7-digit fractional seconds`() {
        val out = mapJellyfinPlays(
            json("""[{"Id":"m5","Type":"Movie","Name":"Heat","ProviderIds":{"tmdb":"949"},"UserData":{"Played":true,"LastPlayedDate":"2026-10-24T20:00:00.1234567Z"}}]"""),
            series, since,
        )
        assertEquals(949, out.single().ids.tmdb)
        assertEquals("2026-10-24", out.single().date)
    }

    // ─── mapJellyfinItems ────────────────────────────────────────────────────

    @Test fun `maps played and partly watched items`() {
        val out = mapJellyfinItems(
            json(
                """[
                {"Id":"a","Type":"Movie","Name":"Heat","ProviderIds":{"Tmdb":"949"},"UserData":{"Played":true,"LastPlayedDate":"2024-05-01T10:00:00Z"}},
                {"Id":"b","Type":"Series","Name":"Slow Horses","RecursiveItemCount":30,"UserData":{"UnplayedItemCount":24}},
                {"Id":"c","Type":"Movie","Name":"Nope","UserData":{"Played":false}}
                ]""",
            ),
        )
        assertEquals(2, out.size)
        assertEquals(SyncProvider.JELLYFIN, out[0].provider)
        assertEquals(listOf("2024-05-01"), out[0].watchedDates)
        assertEquals(MediaType.MOVIE, out[0].type)
        assertEquals(LibraryStatus.WATCHING, out[1].status)
        assertEquals(MediaType.TV, out[1].type)
    }

    // ─── normalizeJellyfinUrl ────────────────────────────────────────────────

    @Test fun `adds https and trims slashes, keeping a base path`() {
        assertEquals("https://media.example.com", normalizeJellyfinUrl("media.example.com/"))
        assertEquals("http://nas.local:8096/jellyfin", normalizeJellyfinUrl("http://nas.local:8096/jellyfin/"))
    }

    @Test fun `rejects empty server address`() {
        assertThrows(IllegalArgumentException::class.java) { normalizeJellyfinUrl(" ") }
    }

    // ─── pending queue ───────────────────────────────────────────────────────

    private fun play(key: String, playedAt: String) = JellyfinPlay(
        key = key, kind = JellyfinPlay.Kind.MOVIE, title = key, date = playedAt.take(10), playedAt = playedAt,
    )
    private val base = JellyfinState(session = null, cursor = since, pending = emptyList())

    @Test fun `queues new plays once and advances the cursor`() {
        val a = play("a", "2026-10-24T11:00:00Z")
        val once = queuePlays(base, listOf(a))
        val twice = queuePlays(once, listOf(a))
        assertEquals(1, twice.pending.size)
        assertEquals(Instant.parse(a.playedAt), Instant.parse(twice.cursor))
    }

    @Test fun `removes a play only when it is resolved`() {
        val queued = queuePlays(base, listOf(play("a", "2026-10-24T11:00:00Z"), play("b", "2026-10-24T12:00:00Z")))
        assertEquals(listOf("b"), resolvePlay(queued, "a").pending.map { it.key })
    }

    @Test fun `state survives a json round trip and corrupt json loads as null`() {
        val session = JellyfinSession("https://j.example", "tok", "u1", "kim")
        val ep = JellyfinPlay(
            key = "e@2026-10-24T10:00:00Z", kind = JellyfinPlay.Kind.EPISODE, title = "Severance",
            ids = ExternalIds(tmdb = 95396, tvdb = 371980), seasonNumber = 1, episodeNumber = 2, episodeName = "Half Loop",
            date = "2026-10-24", playedAt = "2026-10-24T10:00:00Z",
        )
        val state = JellyfinState(session, since, listOf(ep, play("m", "2026-10-24T11:00:00Z")), authExpired = true)
        assertEquals(state, jellyfinStateFromJson(state.toJson()))
        assertNull(jellyfinStateFromJson("{not json"))
        assertNull(jellyfinStateFromJson(null))
    }

    // ─── planEpisodeLog ──────────────────────────────────────────────────────

    @Test fun `logs a known unwatched episode`() =
        assertEquals(EpisodePlan.LOG, planEpisodeLog(EpisodeWatchState("e", emptySet()), "2026-10-24"))

    @Test fun `treats the same day as a duplicate`() =
        assertEquals(EpisodePlan.DUPLICATE, planEpisodeLog(EpisodeWatchState("e", setOf("2026-10-24")), "2026-10-24"))

    @Test fun `reports a missing episode`() = assertEquals(EpisodePlan.MISSING, planEpisodeLog(null, "2026-10-24"))

    @Test fun `describes plays for prompts`() {
        assertEquals("Heat (1995)", play("a", "2026-10-24T11:00:00Z").copy(title = "Heat", year = 1995).describe())
        val ep = play("a", "2026-10-24T11:00:00Z").copy(
            kind = JellyfinPlay.Kind.EPISODE, title = "Severance", seasonNumber = 1, episodeNumber = 2, episodeName = "Half Loop",
        )
        assertEquals("Severance S1E2 · Half Loop", ep.describe())
    }

    // ─── client + watch (no network: canned responses through an interceptor) ─

    private class Canned(val handler: (okhttp3.Request) -> Pair<Int, String>) : Interceptor {
        val requests = mutableListOf<okhttp3.Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val (code, body) = handler(request)
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private fun client(canned: Canned) = JellyfinClient(OkHttpClient.Builder().addInterceptor(canned).build(), "device-1")

    @Test fun `sign in posts to AuthenticateByName and returns the scoped session`() {
        val canned = Canned { 200 to """{"AccessToken":"tok","User":{"Id":"u1","Name":"Kim"}}""" }
        val session = client(canned).signIn("media.example.com/", "kim", "pw")
        assertEquals(JellyfinSession("https://media.example.com", "tok", "u1", "Kim"), session)
        val request = canned.requests.single()
        assertEquals("https://media.example.com/Users/AuthenticateByName", request.url.toString())
        assertTrue(request.header("Authorization")!!.startsWith("MediaBrowser Client=\"CinemArchive\""))
        assertFalse(request.header("Authorization")!!.contains("Token="))
    }

    @Test fun `sign in reports rejected credentials`() {
        val error = assertThrows(IllegalStateException::class.java) {
            client(Canned { 401 to "" }).signIn("media.example.com", "kim", "bad")
        }
        assertEquals("Jellyfin rejected that username or password.", error.message)
    }

    private val items = """{"Items":[
        {"Id":"m1","Type":"Movie","Name":"Heat","ProductionYear":1995,"ProviderIds":{"Tmdb":"949"},"UserData":{"Played":true,"LastPlayedDate":"2026-10-24T20:00:00Z"}},
        {"Id":"e1","Type":"Episode","Name":"Hello","SeriesId":"s1","SeriesName":"Severance","ParentIndexNumber":1,"IndexNumber":1,"UserData":{"Played":true,"LastPlayedDate":"2026-10-24T10:00:00Z"}}
    ]}"""

    private fun serverFor(request: okhttp3.Request): Pair<Int, String> = when {
        request.url.queryParameter("Ids") != null -> 200 to """{"Items":[{"Id":"s1","Type":"Series","Name":"Severance","ProviderIds":{"Tmdb":"95396"}}]}"""
        else -> 200 to items
    }

    @Test fun `fetchPlays asks for the user's own items and resolves series ids`() {
        val canned = Canned(::serverFor)
        val session = JellyfinSession("https://j.example", "tok", "u1", "kim")
        val plays = client(canned).fetchPlays(session, since)
        assertEquals(listOf("Severance", "Heat"), plays.map { it.title })
        assertEquals(95396, plays[0].ids.tmdb)
        assertTrue(canned.requests.all { it.url.encodedPath == "/Users/u1/Items" })
        assertTrue(canned.requests.all { it.header("Authorization")!!.endsWith("Token=\"tok\"") })
    }

    @Test fun `a 401 while polling surfaces as an expired session`() {
        val session = JellyfinSession("https://j.example", "tok", "u1", "kim")
        assertThrows(JellyfinAuthException::class.java) { client(Canned { 401 to "" }).fetchPlays(session, since) }
    }

    private fun watch(canned: Canned, store: JellyfinStore = InMemoryJellyfinStore(), changes: MutableList<Boolean> = mutableListOf()) =
        JellyfinWatch(store, client(canned), { true }, { changes += it }, now = { Instant.parse("2026-10-23T00:00:00Z") })

    private val session = JellyfinSession("https://j.example", "tok", "u1", "kim")

    @Test fun `connecting starts the cursor at now and a check queues each play once`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val w = watch(Canned(::serverFor), changes = changes)
        w.connect(session, alreadyConnected = false)
        assertEquals(listOf(true), changes)

        val first = w.check()
        assertEquals(listOf("Severance", "Heat"), first.map { it.title })
        assertEquals(2, w.view.value.pending.size)

        // Same server answer again: nothing is announced twice, nothing is duplicated.
        assertTrue(w.check().isEmpty())
        assertEquals(2, w.view.value.pending.size)

        w.resolve(first[0].key)
        assertEquals(listOf("Heat"), w.view.value.pending.map { it.title })
        // An answered play is behind the cursor and never prompts again.
        assertTrue(w.check().isEmpty())
        assertEquals(1, w.view.value.pending.size)
    }

    @Test fun `an expired token flags sign-in again and refreshing keeps the cursor and queue`() = runBlocking {
        var expired = false
        val canned = Canned { request -> if (expired) 401 to "" else serverFor(request) }
        val store = InMemoryJellyfinStore()
        val w = watch(canned, store)
        w.connect(session, alreadyConnected = false)
        w.check()
        val cursor = store.read()!!.cursor
        expired = true
        assertTrue(w.check().isEmpty())
        assertTrue(w.view.value.authExpired)

        val renewed = session.copy(token = "tok2")
        w.connect(renewed, alreadyConnected = true)
        assertFalse(w.view.value.authExpired)
        assertEquals(cursor, store.read()!!.cursor)
        assertEquals(2, w.view.value.pending.size)
        assertEquals("tok2", store.read()!!.session!!.token)
    }

    @Test fun `unreachable servers are retried silently and disconnect forgets everything`() = runBlocking {
        val changes = mutableListOf<Boolean>()
        val store = InMemoryJellyfinStore()
        var down = false
        val w = watch(Canned { request -> if (down) 500 to "" else serverFor(request) }, store, changes)
        w.connect(session, alreadyConnected = false)
        down = true
        assertTrue(w.check().isEmpty())
        assertFalse(w.view.value.authExpired)

        down = false
        w.check()
        w.disconnect()
        assertNull(store.read())
        assertFalse(w.view.value.connected)
        assertTrue(w.view.value.pending.isEmpty())
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `jellyfin is a recognised integration provider`() {
        assertEquals(SyncProvider.JELLYFIN, SyncProvider.fromWire("jellyfin"))
        assertEquals(JSONObject().put("provider", "jellyfin").getString("provider"), SyncProvider.JELLYFIN.wire)
    }
}
