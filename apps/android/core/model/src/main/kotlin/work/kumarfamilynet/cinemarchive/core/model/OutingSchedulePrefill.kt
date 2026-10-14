package work.kumarfamilynet.cinemarchive.core.model

/** Public scheduling hints. Seats and other private booking fields are never copied. */
data class OutingSchedulePrefill(val showtime: String, val venue: String?, val format: CinemaFormat?)
