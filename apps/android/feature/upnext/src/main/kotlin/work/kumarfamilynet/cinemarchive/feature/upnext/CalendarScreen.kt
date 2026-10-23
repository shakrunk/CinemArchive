package work.kumarfamilynet.cinemarchive.feature.upnext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import work.kumarfamilynet.cinemarchive.core.designsystem.PosterSurface
import work.kumarfamilynet.cinemarchive.core.designsystem.ReadingWidthColumn
import work.kumarfamilynet.cinemarchive.core.designsystem.tintForKey
import work.kumarfamilynet.cinemarchive.core.model.CalendarEvent
import work.kumarfamilynet.cinemarchive.core.model.calendarEventLabel
import work.kumarfamilynet.cinemarchive.core.model.computeCalendarEvents
import work.kumarfamilynet.cinemarchive.core.model.groupEventsByDay
import work.kumarfamilynet.cinemarchive.core.model.localTodayYmd
import work.kumarfamilynet.cinemarchive.core.model.monthGrid
import work.kumarfamilynet.cinemarchive.data.CalendarSources
import work.kumarfamilynet.cinemarchive.data.LibraryRepository
import work.kumarfamilynet.cinemarchive.core.designsystem.LocalBottomBarClearance

private val Weekdays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** The Calendar view (issue #340), opened from the Up Next header, the command palette or a
 *  `?view=calendar` link. Mirrors `apps/web/src/views/Calendar.tsx`. Only ever hosted in the
 *  owner's own archive, so its outings are the owner's; shared/friend archives never reach it. */
@Composable
fun CalendarRoute(
    repository: LibraryRepository,
    onTitleClick: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseDiscover: () -> Unit,
) {
    val sourcesFlow = remember(repository) { repository.observeCalendar() }
    val sources by sourcesFlow.collectAsStateWithLifecycle(initialValue = null)
    CalendarScreen(sources, onTitleClick, onBack, onBrowseDiscover)
}

/** Local "today", re-derived each minute so the view rolls over at midnight. */
@Composable
private fun rememberLocalToday(): String {
    val today by produceState(localTodayYmd()) {
        while (true) {
            delay(60_000)
            value = localTodayYmd()
        }
    }
    return today
}

@Composable
fun CalendarScreen(
    sources: CalendarSources?,
    onTitleClick: (String) -> Unit,
    onBack: () -> Unit,
    onBrowseDiscover: () -> Unit,
) {
    val today = rememberLocalToday()
    val events = remember(sources, today) {
        sources?.let { computeCalendarEvents(it.titles, it.outings, today) }.orEmpty()
    }
    val eventsByDate = remember(events) { groupEventsByDay(events).associate { it.date to it.events } }

    val currentMonth = YearMonth.from(LocalDate.parse(today))
    var cursorKey by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    val cursor = YearMonth.parse(cursorKey)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val isCurrentMonth = cursor <= currentMonth
    val monthLabel = cursor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
    val cells = remember(cursor) { monthGrid(cursor) }
    val agenda = remember(selected, events, cursor) {
        val day = selected
        if (day != null) listOf(day to eventsByDate[day].orEmpty())
        else groupEventsByDay(events.filter { YearMonth.from(LocalDate.parse(it.date)) == cursor }).map { it.date to it.events }
    }

    fun shiftMonth(delta: Long) {
        selected = null
        cursorKey = cursor.plusMonths(delta).toString()
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp + LocalBottomBarClearance.current),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Up Next")
                }
                Column {
                    Text("COMING SOON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Calendar", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                }
            }
        }

        if (sources == null) return@LazyColumn

        if (events.isEmpty()) {
            item {
                ReadingWidthColumn {
                    Text("Nothing on the horizon.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Upcoming episodes of your series and release dates of watchlist movies will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onBrowseDiscover, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Find something upcoming")
                    }
                }
            }
            return@LazyColumn
        }

        item {
            ReadingWidthColumn {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { shiftMonth(-1) }, enabled = !isCurrentMonth) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                        }
                        Text(
                            monthLabel,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { shiftMonth(1) }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                        }
                    }
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                        Weekdays.forEach {
                            Text(
                                it.uppercase(Locale.US),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                            )
                        }
                    }
                    cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { date ->
                                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                                    if (date != null) {
                                        DayCell(
                                            date = date,
                                            events = eventsByDate[date].orEmpty(),
                                            isToday = date == today,
                                            isPast = date < today,
                                            isSelected = date == selected,
                                            onClick = { selected = if (selected == date) null else date },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    events.firstOrNull()?.let { next ->
                        Text(
                            "Next up: ${next.title.name} · ${dayHeading(next.date, today)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        )
                    }
                }
            }
        }

        if (selected != null) {
            item {
                ReadingWidthColumn {
                    TextButton(onClick = { selected = null }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("← Show all of $monthLabel")
                    }
                }
            }
        }

        if (agenda.isEmpty()) {
            item {
                ReadingWidthColumn {
                    Text(
                        "Nothing scheduled in $monthLabel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            }
        }

        agenda.forEach { (date, dayEvents) ->
            item(key = "day-$date") {
                ReadingWidthColumn {
                    Text(
                        dayHeading(date, today).uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
                    )
                }
            }
            items(dayEvents, key = { it.id }) { event ->
                ReadingWidthColumn { EventRow(event, onClick = { onTitleClick(event.title.id) }) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: String,
    events: List<CalendarEvent>,
    isToday: Boolean,
    isPast: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hasEvents = events.isNotEmpty()
    val description = buildString {
        append(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMMM d", Locale.US)))
        if (hasEvents) append(", ${events.size} event${if (events.size == 1) "" else "s"}")
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .then(if (isSelected) Modifier.background(colors.primary.copy(alpha = 0.15f)) else Modifier)
            .then(if (isToday) Modifier.border(1.dp, colors.primary.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(enabled = hasEvents, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
            },
    ) {
        Text(
            date.takeLast(2).trimStart('0'),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isSelected -> colors.primary
                hasEvents -> colors.onSurface
                isPast -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                else -> colors.onSurfaceVariant
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 3.dp).height(6.dp)) {
            events.take(3).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor(it))) }
        }
    }
}

@Composable
private fun dotColor(event: CalendarEvent): Color = when (event) {
    is CalendarEvent.Outing -> MaterialTheme.colorScheme.tertiary
    is CalendarEvent.Premiere, is CalendarEvent.Release -> MaterialTheme.colorScheme.primary
    is CalendarEvent.Episodes -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun CalendarEvent.icon(): ImageVector = when (this) {
    is CalendarEvent.Outing -> Icons.Filled.ConfirmationNumber
    is CalendarEvent.Premiere -> Icons.Filled.AutoAwesome
    is CalendarEvent.Release -> Icons.Filled.Movie
    is CalendarEvent.Episodes -> Icons.Filled.Tv
}

private fun rowLabel(event: CalendarEvent): String {
    if (event !is CalendarEvent.Outing) return calendarEventLabel(event)
    val time = runCatching {
        Instant.parse(event.outing.showtime).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    }.getOrNull()
    return listOfNotNull("Tickets", time, event.outing.venue?.takeIf(String::isNotBlank)).joinToString(" · ")
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        PosterSurface(
            tint = tintForKey(event.title.id),
            imageUrl = event.title.posterUrl,
            modifier = Modifier.size(width = 42.dp, height = 60.dp),
            aspectRatio = 42f / 60f,
            cornerRadius = 10.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(event.title.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(event.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(
                    rowLabel(event),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Today · Sunday, October 18" / "Tomorrow · …" / "Tuesday, October 20". Dates are parsed as
 *  local days, never as instants, so no timezone shift can move them. */
private fun dayHeading(date: String, today: String): String {
    val day = LocalDate.parse(date)
    val label = day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
    val todayDate = LocalDate.parse(today)
    return when (day) {
        todayDate -> "Today · $label"
        todayDate.plusDays(1) -> "Tomorrow · $label"
        else -> label
    }
}
