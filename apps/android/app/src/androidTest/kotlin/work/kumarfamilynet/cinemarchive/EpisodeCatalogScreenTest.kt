package work.kumarfamilynet.cinemarchive

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

/** Isolated synthetic Room archive; no existing device account or network is touched. */
@RunWith(AndroidJUnit4::class)
class EpisodeCatalogScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun scrollTo(text:String)=compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        .performScrollToNode(hasText(text))

    @Test fun queuedParentAppearsAfterCanonicalAckAndWatchLogUsesCanonicalEpisodeId() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="catalog-device-${UUID.randomUUID()}.db"
        val db=LibraryDatabase.create(context,name)
        val owner=UUID.randomUUID().toString(); val titleId=UUID.randomUUID().toString()
        val seasonId=UUID.randomUUID().toString(); val canonicalEpisode=UUID.randomUUID().toString()
        val writer=object:RemoteMutationWriter {
            override suspend fun push(entry:OutboxEntity):PushResult {
                check(entry.entityType=="title_catalog")
                val operations=JSONObject(entry.payloadJson).getJSONArray("operations")
                val rows=JSONArray(); val current=JSONArray()
                for(index in 0 until operations.length()) {
                    val op=operations.getJSONObject(index); val table=op.getString("table"); val key=op.getJSONObject("key")
                    val row=JSONObject(op.getJSONObject("values").toString())
                    key.keys().forEach { row.put(it,key.get(it)) }
                    row.put("user_id",owner).put("id",when(table) { "titles"->titleId; "seasons"->seasonId; else->canonicalEpisode })
                    if(table=="seasons") row.put("episodes_watched",0)
                    val result=JSONObject().put("table",table).put("key",key).put("row",row)
                    rows.put(result); if(table!="titles") current.put(result)
                }
                return PushResult.Applied(JSONObject().put("receipt",JSONObject().put("operationId",entry.id).put("rows",rows)).put("currentRows",current))
            }
        }
        val outbox=MutationOutbox(db.outboxDao(),writer,TitleConflictHandler(db.titleDao()),RoomTransactor(db),EpisodeCatalogFillApplier(db,owner))
        val repository=LibraryRepository(db.titleDao(),db.seasonDao(),db.episodeDao(),db.episodeWatchEventDao(),db.episodeRatingDao(),db.episodeReviewDao(),
            db.viewingDao(),db.cinemaOutingDao(),db.titleCastDao(),db.titleCrewDao(),db.theaterInterestDao(),outbox,
            object:EpisodeMetadataFetcher {
                override suspend fun fetchSeasonEpisodes(tmdbId:Int,seasonNumber:Int)=emptyList<MediaEpisode>()
                override suspend fun fetchEpisodeCast(tmdbId:Int,seasonNumber:Int,episodeNumber:Int)=EpisodeCast.EMPTY
            },personCreditsDao=db.personCreditsDao())
        val refresh=CreditRefreshRepository(db,outbox,CreditMetadataFetcher {
            MediaDetails(42,MediaType.TV,"Synthetic catalog show",2020,null,null,emptyList(),null,null,null,null,null,null,null,null,
                seasons=listOf(MediaSeason(1,1,2020,listOf(MediaEpisode(1,"Recovered episode",null,30)))))
        },owner,{true})
        try {
            runBlocking(Dispatchers.IO) {
                db.titleDao().upsertAll(listOf(TitleEntity(titleId,42,"TV","Synthetic catalog show",2020,null,emptyList(),null,null,null,30,null,"WATCHING",null,null,"2026-01-01","2026-01-01")))
                db.seasonDao().upsertAll(listOf(SeasonEntity(seasonId,titleId,1,1,0,2020)))
            }
            compose.setContent { CinemArchiveTheme {
                val detail by repository.observeTitleDetail(titleId).collectAsState(initial=null)
                detail?.let { TitleDetailScreen(it,{},onRefreshCredits={refresh.refresh(titleId)},onSaveEpisodeLog=repository::saveEpisodeLog) }
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic catalog show").fetchSemanticsNodes().isNotEmpty() }
            scrollTo("Refresh credits"); compose.onNodeWithText("Refresh credits").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Credit refresh saved. Missing episodes will appear after sync.").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { assertTrue(db.episodeDao().observeEpisodes(titleId).first().isEmpty()) }
            compose.onAllNodesWithText("Log watch, rating, or review").assertCountEquals(0)
            runBlocking(Dispatchers.IO) { outbox.flush(); assertTrue(db.outboxDao().getPending().isEmpty()) }
            compose.waitForIdle()
            scrollTo("Log watch, rating, or review"); compose.onNodeWithText("Log watch, rating, or review").performClick()
            compose.onNodeWithText("Watch notes (optional)").performScrollTo().performTextInput("Canonical parent watch")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save").fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val event=db.episodeWatchEventDao().observeAllWatchEvents().first().single()
                assertEquals(canonicalEpisode,event.episodeId)
                assertEquals("Canonical parent watch",event.notes)
                assertEquals(canonicalEpisode,JSONObject(db.outboxDao().getPending().single().payloadJson).getString("episodeId"))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
