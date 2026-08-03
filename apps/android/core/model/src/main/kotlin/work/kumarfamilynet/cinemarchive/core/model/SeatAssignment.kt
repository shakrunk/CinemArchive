package work.kumarfamilynet.cinemarchive.core.model

/**
 * Where you're sitting, in the order you need it walking into a multiplex: the auditorium
 * first (that's the door you're looking for), then the row, then the seats. Mirrors the web
 * app's `apps/web/src/lib/seating.ts` — the two clients must format the same outing the same
 * way, since one can be scheduled on either and read on the other.
 *
 * [seat] is the pre-#221 free-text column. It's the fallback, never the preferred value: rows
 * written before the split hold whatever the user typed ("F12-13", "Row F, seats 12 and 13"),
 * which is unsafe to parse but perfectly fine to display verbatim.
 */
data class SeatAssignment(
    val auditorium: String? = null,
    val seatRow: String? = null,
    val seats: List<String> = emptyList(),
    val seat: String? = null,
) {
    /** True as soon as any one part of the structured trio is filled in. */
    val isStructured: Boolean
        get() = !auditorium.isNullOrBlank() || !seatRow.isNullOrBlank() || seats.any { it.isNotBlank() }

    /** "Theatre 7" for a bare number, the venue's own wording otherwise ("Grand Hall",
     *  "IMAX 1") — vendors are inconsistent about including the noun, and prefixing one onto
     *  "Grand Hall" reads badly. */
    val auditoriumLabel: String?
        get() = auditorium?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.all(Char::isDigit)) "Theatre $it" else it }

    val rowLabel: String?
        get() = seatRow?.trim()?.takeIf { it.isNotEmpty() }?.let { "Row $it" }

    /** "Seat 12" for one, "Seats 12, 13" for a party. */
    val seatsLabel: String?
        get() {
            val clean = seats.map(String::trim).filter(String::isNotEmpty)
            if (clean.isEmpty()) return null
            return "${if (clean.size == 1) "Seat" else "Seats"} ${clean.joinToString(", ")}"
        }

    /** One line — "Theatre 7 · Row F · Seats 12, 13" — falling back to the legacy free-text
     *  string verbatim, and to null when the outing has no seating recorded at all. */
    val line: String?
        get() {
            if (!isStructured) return seat?.trim()?.takeIf { it.isNotEmpty() }
            return listOfNotNull(auditoriumLabel, rowLabel, seatsLabel)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")
        }

    /** The compact form for running prose and calendar entries — "F12", "F12, F13" — where
     *  the full line would swamp the sentence around it. Row and seat run together the way a
     *  printed ticket prints them. */
    val short: String?
        get() {
            if (!isStructured) return seat?.trim()?.takeIf { it.isNotEmpty() }
            val row = seatRow?.trim().orEmpty()
            val clean = seats.map(String::trim).filter(String::isNotEmpty)
            if (clean.isEmpty()) return row.takeIf { it.isNotEmpty() } ?: auditoriumLabel
            return clean.joinToString(", ") { "$row$it" }
        }

    companion object {
        /** Parses the comma/space-separated entry a text field produces: "12, 13" and
         *  "12 13" both give ["12", "13"]. */
        fun parseSeats(value: String): List<String> =
            value.split(',', ' ', '\t').map(String::trim).filter(String::isNotEmpty)
    }
}

/** The seat assignment of an outing, ready to format. */
val CinemaOuting.seating: SeatAssignment
    get() = SeatAssignment(auditorium = auditorium, seatRow = seatRow, seats = seats, seat = seat)
