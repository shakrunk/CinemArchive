package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/**
 * Letterboxd CSV import — the Kotlin counterpart of `apps/web/src/lib/letterboxd-import.ts`.
 * Letterboxd's data export is a zip of CSVs (watched/ratings/diary/watchlist) sharing the core
 * columns `Date,Name,Year,Letterboxd URI`, with `Rating` and `Watched Date` in the ratings and
 * diary files. The CSVs carry no TMDB/IMDb ids, so items resolve by name+year through
 * [pickBestMatch]. Letterboxd rates on the same 0.5–5 half-star scale as this app.
 *
 * Rows become [SyncItem]s and go through the same [SyncRepository.import] pipeline as Simkl,
 * Plex and Emby, so re-importing a newer diary adds viewings and fills empty ratings on films
 * already in the library rather than skipping them.
 */
data class LetterboxdRow(
    val name: String,
    val year: Int?,
    /** 0.5–5 half stars — same scale as the app. */
    val rating: Double?,
    /** `YYYY-MM-DD`; diary.csv's "Watched Date" when present, else the log "Date". */
    val watchedDate: String?,
)

/** Minimal RFC-4180 reader: quoted fields, doubled-quote escapes, and commas/newlines inside
 *  quotes (Letterboxd titles contain all three). */
internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQuotes) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
            } else {
                field.append(ch)
            }
        } else when (ch) {
            '"' -> inQuotes = true
            ',' -> { row.add(field.toString()); field.clear() }
            '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
            '\r' -> Unit
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
    return rows
}

/** Parses any of Letterboxd's export CSVs. Throws when the file has no `Name` column. */
fun parseLetterboxdCsv(text: String): List<LetterboxdRow> {
    val rows = parseCsv(text.removePrefix("﻿"))
    require(rows.isNotEmpty()) { "The file is empty." }
    val header = rows[0].map { it.trim().lowercase() }
    val nameIdx = header.indexOf("name")
    require(nameIdx != -1) { "Not a Letterboxd export — no \"Name\" column found." }
    val yearIdx = header.indexOf("year")
    val ratingIdx = header.indexOf("rating")
    val watchedDateIdx = header.indexOf("watched date")
    val dateIdx = header.indexOf("date")
    val dateOnly = Regex("""\d{4}-\d{2}-\d{2}""")

    return rows.drop(1).mapNotNull { cells ->
        val name = cells.getOrNull(nameIdx)?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val rating = ratingIdx.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.toDoubleOrNull() }
        // diary.csv's "Date" is the log date; "Watched Date" is the real one.
        val watched = (if (watchedDateIdx >= 0) cells.getOrNull(watchedDateIdx) else dateIdx.takeIf { it >= 0 }?.let { cells.getOrNull(it) })?.trim()
        LetterboxdRow(
            name = name,
            year = yearIdx.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim()?.toIntOrNull() },
            rating = rating?.takeIf { it > 0 },
            watchedDate = watched?.takeIf { dateOnly.matches(it) },
        )
    }
}

private data class GroupedFilm(val name: String, val year: Int?, var rating: Double?, var ratingDate: String?, val dates: MutableList<String>)

/**
 * Maps rows to sync items, one per film: diary exports repeat a film per rewatch, so watch
 * dates accumulate and the rating from the most recent watch wins (an undated rating row —
 * ratings.csv is the *current* rating — always wins).
 */
fun letterboxdToSyncItems(rows: List<LetterboxdRow>, status: LibraryStatus): List<SyncItem> {
    val byKey = LinkedHashMap<String, GroupedFilm>()
    for (r in rows) {
        val key = "${normTitle(r.name)}:${r.year ?: ""}"
        val film = byKey.getOrPut(key) { GroupedFilm(r.name, r.year, null, null, mutableListOf()) }
        if (r.rating != null) {
            val incoming = r.watchedDate ?: "9999-99-99"
            if (film.ratingDate == null || incoming >= film.ratingDate!!) {
                film.rating = r.rating
                film.ratingDate = incoming
            }
        }
        r.watchedDate?.let { film.dates += it }
    }
    return byKey.map { (key, film) ->
        SyncItem(
            provider = SyncProvider.LETTERBOXD,
            externalId = key,
            type = MediaType.MOVIE,
            title = film.name,
            year = film.year,
            ids = ExternalIds(),
            status = status,
            rating = film.rating,
            watchedDates = film.dates.sorted(),
        )
    }
}
