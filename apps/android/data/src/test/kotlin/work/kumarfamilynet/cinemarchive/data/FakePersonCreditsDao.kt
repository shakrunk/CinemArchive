package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import work.kumarfamilynet.cinemarchive.core.database.*

internal class FakePersonCreditsDao : PersonCreditsDao {
    val seasonCast = MutableStateFlow<List<SeasonCastEntity>>(emptyList())
    val episodeCrew = MutableStateFlow<List<EpisodeCrewEntity>>(emptyList())
    override suspend fun upsertSeasonCast(rows: List<SeasonCastEntity>) { seasonCast.value = (seasonCast.value + rows).associateBy { it.id }.values.toList() }
    override suspend fun upsertEpisodeCrew(rows: List<EpisodeCrewEntity>) { episodeCrew.value = (episodeCrew.value + rows).associateBy { it.id }.values.toList() }
    override suspend fun deleteSeasonCast(id: String) { seasonCast.value = seasonCast.value.filterNot { it.id == id } }
    override suspend fun deleteEpisodeCrew(id: String) { episodeCrew.value = episodeCrew.value.filterNot { it.id == id } }
    override fun observeSeasonCast() = seasonCast
    override fun observeEpisodeCrew() = episodeCrew
    override suspend fun hasSeason(titleId: String, seasonId: String) = true
    override suspend fun hasEpisode(titleId: String, episodeId: String) = true
    override fun observeLibraryPeople() = flowOf(emptyList<LibraryPersonRow>())
}
