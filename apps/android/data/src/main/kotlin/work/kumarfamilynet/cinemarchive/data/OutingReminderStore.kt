package work.kumarfamilynet.cinemarchive.data

import java.time.Instant

/**
 * The device's "N minutes before showtime" setting and which reminders it has already posted
 * (#213). Both are per device on purpose: the lead is a notification preference, and a second
 * phone must not stay silent because the first one already announced the show. The real store is
 * [PreferencesRepository]; [OutingsRepository] depends on this seam.
 */
interface OutingReminderStore {
    /** Minutes before showtime to remind, one of `OutingReminderRules.LEAD_OPTIONS_MINUTES`, or null when off. */
    suspend fun leadMinutes(): Int?

    suspend fun sentKeys(): Set<String>

    suspend fun saveSentKeys(keys: Set<String>)
}

/** Reminders off until the app wires a real store, like [NoOpOutingAlarmScheduler]. */
object NoOpOutingReminderStore : OutingReminderStore {
    override suspend fun leadMinutes(): Int? = null
    override suspend fun sentKeys(): Set<String> = emptySet()
    override suspend fun saveSentKeys(keys: Set<String>) = Unit
}

/** A reminder claimed for posting: the show starts at [showtime]. */
data class OutingReminder(
    val outingId: String,
    val titleId: String,
    val titleName: String,
    val posterUrl: String?,
    val showtime: Instant,
    val venue: String?,
)
