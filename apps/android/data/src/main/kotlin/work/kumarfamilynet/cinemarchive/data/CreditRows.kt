package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.first
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*

internal data class CreditRow(
    val table: String, val id: String, val titleId: String, val parentId: String,
    val personId: Int, val name: String, val role: String?, val order: Int = 0, val department: String? = null,
) {
    val identity: String get() = "$table:$parentId:$personId:${if (table.endsWith("crew")) role else ""}"
    fun key() = JSONObject().put(when (table) { "season_cast" -> "season_id"; "episode_crew" -> "episode_id"; else -> "title_id" }, parentId)
        .put("tmdb_person_id", personId).apply { if (table.endsWith("crew")) put("job", role) }
    fun values() = JSONObject().put("name", name).apply {
        if (table == "season_cast" || table == "episode_crew") put("title_id", titleId)
        if (table.endsWith("cast")) { put("character_name", role ?: JSONObject.NULL); put("cast_order", order) }
        if (table == "title_crew") put("department", department ?: JSONObject.NULL)
    }
}

internal suspend fun readCreditRows(db: LibraryDatabase, titleId: String): List<CreditRow> =
    db.titleCastDao().observeAllCast().first().filter { it.titleId == titleId }.map {
        CreditRow("title_cast", it.id, titleId, titleId, it.tmdbPersonId, it.name, it.characterName, it.castOrder)
    } + db.titleCrewDao().observeAllCrew().first().filter { it.titleId == titleId }.map {
        CreditRow("title_crew", it.id, titleId, titleId, it.tmdbPersonId, it.name, it.job, department = it.department)
    } + db.personCreditsDao().observeSeasonCast().first().filter { it.titleId == titleId }.map {
        CreditRow("season_cast", it.id, titleId, it.seasonId, it.tmdbPersonId, it.name, it.characterName, it.castOrder)
    } + db.personCreditsDao().observeEpisodeCrew().first().filter { it.titleId == titleId }.map {
        CreditRow("episode_crew", it.id, titleId, it.episodeId, it.tmdbPersonId, it.name, it.job)
    }

internal suspend fun deleteCreditRow(db: LibraryDatabase, row: CreditRow) = when (row.table) {
    "title_cast" -> db.titleCastDao().deleteById(row.id)
    "title_crew" -> db.titleCrewDao().deleteById(row.id)
    "season_cast" -> db.personCreditsDao().deleteSeasonCast(row.id)
    "episode_crew" -> db.personCreditsDao().deleteEpisodeCrew(row.id)
    else -> error("Unknown credit table")
}

internal suspend fun writeCreditRows(db: LibraryDatabase, rows: List<CreditRow>) {
    db.titleCastDao().upsertAll(rows.filter { it.table == "title_cast" }.map { TitleCastEntity(it.id,it.titleId,it.personId,it.name,it.role,it.order) })
    db.titleCrewDao().upsertAll(rows.filter { it.table == "title_crew" }.map { TitleCrewEntity(it.id,it.titleId,it.personId,it.name,it.role!!,it.department) })
    db.personCreditsDao().upsertSeasonCast(rows.filter { it.table == "season_cast" }.map { SeasonCastEntity(it.id,it.titleId,it.parentId,it.personId,it.name,it.role,it.order) })
    db.personCreditsDao().upsertEpisodeCrew(rows.filter { it.table == "episode_crew" }.map { EpisodeCrewEntity(it.id,it.titleId,it.parentId,it.personId,it.name,it.role!!) })
}
