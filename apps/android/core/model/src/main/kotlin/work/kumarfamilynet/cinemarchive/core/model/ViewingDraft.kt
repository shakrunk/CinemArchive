package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate

/** The editor retains [id] through retries; null date means watched before joining. */
data class ViewingDraft(
    val id: String,
    val date: String?,
    val rating: Double?,
    val notes: String?,
    val venue: String?,
    val companions: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        date?.let { LocalDate.parse(it) }
        require(rating == null || (rating.isFinite() && rating in 0.5..5.0 && rating * 2 % 1 == 0.0))
    }
}
