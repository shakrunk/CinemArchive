package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Fixture data for the FAB-triggered "New Title" Add overlay ([AddTitleOverlayRoute]). The
 * Discover tab itself now shows real trending titles via
 * [work.kumarfamilynet.cinemarchive.data.DiscoverRepository] (see [DiscoverScreen.kt]'s
 * [work.kumarfamilynet.cinemarchive.feature.discover.DiscoverRoute]); the Add overlay's search
 * is still this local sample catalog, since real TMDB search/add is a separate, larger scope
 * (docs/android-parity-matrix.md's Discover/add row) — deliberately kept apart from
 * [work.kumarfamilynet.cinemarchive.data.LibraryRepository]'s real, Room-backed library.
 * Swap this module out once real search lands; nothing downstream should need to change.
 */
data class SampleTitle(
    val id: String,
    val title: String,
    val year: Int,
    val type: MediaType,
    val tint: Color,
    val synopsis: String,
)

val SAMPLE_CATALOG: List<SampleTitle> = listOf(
    SampleTitle("smp1", "Chinatown", 1974, MediaType.MOVIE, Color(0xFF6B7480), "A private eye is drawn into a web of deception and family secrets in 1930s Los Angeles."),
    SampleTitle("smp2", "Paris, Texas", 1984, MediaType.MOVIE, Color(0xFF8A6A4F), "A man who has wandered for years returns to reconnect with the son he left behind."),
    SampleTitle("smp3", "Spirited Away", 2001, MediaType.MOVIE, Color(0xFF506B66), "A girl wandering a mysterious spirit world must find the courage to save her parents."),
    SampleTitle("smp4", "The Handmaiden", 2016, MediaType.MOVIE, Color(0xFF7A5C6E), "A conman and a swindler scheme against an heiress, and nothing is quite as it seems."),
    SampleTitle("smp5", "Twin Peaks", 1990, MediaType.TV, Color(0xFF5E5A72), "An FBI agent investigates a homecoming queen's murder in a deeply strange logging town."),
    SampleTitle("smp6", "The Wire", 2002, MediaType.TV, Color(0xFF5C6B5A), "The Baltimore drug trade, seen through the eyes of the police and the players on the street."),
    SampleTitle("smp7", "Fleabag", 2016, MediaType.TV, Color(0xFF7C6B54), "A woman navigates grief, guilt and desire in modern London, one fourth-wall break at a time."),
    SampleTitle("smp8", "Oldboy", 2003, MediaType.MOVIE, Color(0xFF4F6675), "A man seeks revenge after being mysteriously imprisoned for fifteen years without explanation."),
    SampleTitle("smp9", "Blade Runner 2049", 2017, MediaType.MOVIE, Color(0xFF506B66), "A young blade runner unearths a secret that could plunge what's left of society into chaos."),
    SampleTitle("smp10", "The Bear", 2022, MediaType.TV, Color(0xFF8A6A4F), "A young chef returns home to run his family's chaotic Chicago sandwich shop."),
    SampleTitle("smp11", "Severance", 2022, MediaType.TV, Color(0xFF7A5C6E), "Employees surgically divide their memories between work and home, with unnerving results."),
    SampleTitle("smp12", "Perfect Days", 2023, MediaType.MOVIE, Color(0xFF6B7480), "A Tokyo toilet cleaner finds quiet contentment in routine, music, and small daily rituals."),
)

/** Process-lifetime "added to library" state, shared by the sample catalog's Add overlay and
 *  Discover's real trending grid (keyed by tmdbId there) — deliberately not persisted (see
 *  [SampleTitle] kdoc): this is a demo of the add *interaction*, not a real library write. */
object DiscoverSampleStore {
    private val _addedIds = MutableStateFlow<Set<String>>(emptySet())
    val addedIds: StateFlow<Set<String>> = _addedIds

    fun add(id: String) {
        _addedIds.update { it + id }
    }
}
