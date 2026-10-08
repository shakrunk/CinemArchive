package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity

/** Runs inside MutationOutbox's ACK transaction, including the dependent credit command. */
class EpisodeCatalogFillApplier(private val database: LibraryDatabase, private val ownerId: String) : AppliedMutationHandler {
    override suspend fun apply(entry: OutboxEntity, receipt: JSONObject) {
        val current = currentEpisodeCatalogRows(entry, receipt, ownerId)
        if (database.titleDao().getById(entry.entityId) == null) return
        val catalog = JSONObject(entry.payloadJson).getJSONArray("catalog")
        val pendingCreditOps = database.outboxDao().getPending().filter { it.entityType == "title_credits" && it.entityId == entry.entityId }
            .flatMap { queued -> JSONObject(queued.payloadJson).getJSONArray("operations").let { ops ->
                (0 until ops.length()).map(ops::getJSONObject)
            } }
        val old = readCreditRows(database, entry.entityId)
        val changed = mutableListOf<CreditRow>()
        fun credit(row: CreditRow) {
            // A newer local refresh/removal retains priority over this fetched snapshot.
            if (pendingCreditOps.any { it.getString("table") == row.table && sameCommandJson(it.getJSONObject("key"), row.key()) }) return
            val previous = old.firstOrNull { it.identity == row.identity }
            val next = row.copy(id = previous?.id ?: UUID.randomUUID().toString())
            if (next != previous) changed += next
        }
        for (index in 0 until catalog.length()) {
            val planned = catalog.getJSONObject(index)
            val number = planned.getInt("seasonNumber")
            val serverSeason = current.singleOrNull { it.getString("table") == "seasons" && it.getJSONObject("key").getInt("season_number") == number }
                ?.getJSONObject("row") ?: continue // Deleted since the historical receipt.
            val localSeason = database.seasonDao().observeSeasons(entry.entityId).first().singleOrNull { it.seasonNumber == number }
            if (!planned.isNull("localSeasonId") && localSeason == null) continue // Locally removed while in flight.
            val seasonId = serverSeason.getString("id")
            require(localSeason == null || localSeason.id == seasonId) { "Season identity needs reconciliation; saved history has been retained" }
            if (localSeason == null) {
                val count = serverSeason.getInt("episode_count")
                val watched = serverSeason.getInt("episodes_watched")
                require(count >= 0 && watched in 0..count)
                database.seasonDao().upsertAll(listOf(SeasonEntity(seasonId, entry.entityId, number, count, watched, serverSeason.nullInt("air_year"))))
            }
            val cast = planned.getJSONArray("cast")
            for (castIndex in 0 until cast.length()) {
                val person = cast.getJSONObject(castIndex)
                credit(CreditRow("season_cast", "", entry.entityId, seasonId, person.getInt("personId"),
                    person.getString("name"), person.nullText("character"), person.getInt("order")))
            }
            val plannedEpisodes = planned.getJSONArray("episodes")
            for (episodeIndex in 0 until plannedEpisodes.length()) {
                val plannedEpisode = plannedEpisodes.getJSONObject(episodeIndex)
                val episodeNumber = plannedEpisode.getInt("episodeNumber")
                val serverEpisode = current.singleOrNull { it.getString("table") == "episodes" &&
                    it.getJSONObject("key").getInt("season_number") == number && it.getJSONObject("key").getInt("episode_number") == episodeNumber }
                    ?.getJSONObject("row") ?: continue
                val localEpisode = database.episodeDao().observeEpisodes(entry.entityId).first()
                    .singleOrNull { it.seasonId == seasonId && it.episodeNumber == episodeNumber }
                val episodeId = serverEpisode.getString("id")
                require(localEpisode == null || localEpisode.id == episodeId) { "Episode identity needs reconciliation; saved history has been retained" }
                if (localEpisode == null) database.episodeDao().upsertAll(listOf(EpisodeEntity(
                    episodeId, entry.entityId, seasonId, episodeNumber, serverEpisode.nullText("episode_name"),
                    serverEpisode.nullText("air_date"), serverEpisode.nullInt("runtime"), serverEpisode.nullText("synopsis"), serverEpisode.nullText("still_url"),
                )))
                val crew = plannedEpisode.getJSONArray("crew")
                for (crewIndex in 0 until crew.length()) {
                    val person = crew.getJSONObject(crewIndex)
                    credit(CreditRow("episode_crew", "", entry.entityId, episodeId, person.getInt("personId"), person.getString("name"), person.getString("job")))
                }
            }
        }
        if (changed.isEmpty()) return
        val operations = JSONArray().put(JSONObject().put("table", "titles").put("action", "update")
            .put("key", JSONObject().put("id", entry.entityId)).put("values", JSONObject()))
        changed.forEach { operations.put(JSONObject().put("table", it.table).put("action", "put").put("key", it.key()).put("values", it.values())) }
        writeCreditRows(database, changed)
        database.outboxDao().enqueue(OutboxEntity(UUID.randomUUID().toString(), "title_credits", entry.entityId, "refresh",
            JSONObject().put("ownerId", ownerId).put("titleId", entry.entityId).put("operations", operations)
                .put("protectedKeys", JSONArray(changed.map { "${it.table}:${it.id}" })).toString(), System.currentTimeMillis()))
    }
}

private fun JSONObject.nullText(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.nullInt(key: String): Int? = if (isNull(key)) null else getInt(key)
