package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors apps/web/src/lib/seating.test.ts — the two clients must format the same outing
 *  identically, since one can be scheduled on either and read on the other. */
class SeatAssignmentTest {

    @Test
    fun `a legacy free-text seat alone is not structured`() {
        assertFalse(SeatAssignment(seat = "F12-13").isStructured)
    }

    @Test
    fun `any one part of the trio makes it structured`() {
        assertTrue(SeatAssignment(auditorium = "7").isStructured)
        assertTrue(SeatAssignment(seatRow = "F").isStructured)
        assertTrue(SeatAssignment(seats = listOf("12")).isStructured)
    }

    @Test
    fun `a blank trio is not structured`() {
        assertFalse(SeatAssignment(auditorium = "  ", seatRow = "", seats = listOf(" ")).isStructured)
    }

    @Test
    fun `a bare auditorium number is named so it reads as a place`() {
        assertEquals("Theatre 7", SeatAssignment(auditorium = "7").auditoriumLabel)
    }

    @Test
    fun `a venue's own auditorium wording is left alone`() {
        assertEquals("Grand Hall", SeatAssignment(auditorium = "Grand Hall").auditoriumLabel)
        assertEquals("IMAX 1", SeatAssignment(auditorium = "IMAX 1").auditoriumLabel)
    }

    @Test
    fun `seats are singularized for one and listed for a party`() {
        assertEquals("Seat 12", SeatAssignment(seats = listOf("12")).seatsLabel)
        assertEquals("Seats 12, 13", SeatAssignment(seats = listOf("12", "13")).seatsLabel)
        assertNull(SeatAssignment(seats = listOf(" ", "")).seatsLabel)
    }

    @Test
    fun `the line orders the trio the way you need it walking in`() {
        val seating = SeatAssignment(auditorium = "7", seatRow = "F", seats = listOf("12", "13"))
        assertEquals("Theatre 7 · Row F · Seats 12, 13", seating.line)
    }

    @Test
    fun `the line omits the parts that are missing`() {
        assertEquals("Theatre 7", SeatAssignment(auditorium = "7").line)
        assertEquals("Row F · Seat 12", SeatAssignment(seatRow = "F", seats = listOf("12")).line)
    }

    @Test
    fun `the line falls back to the legacy string verbatim`() {
        assertEquals("Row F, seats 12 and 13", SeatAssignment(seat = "Row F, seats 12 and 13").line)
    }

    @Test
    fun `the structured trio wins over a stale legacy string`() {
        assertEquals("Theatre 7", SeatAssignment(auditorium = "7", seat = "H12").line)
    }

    @Test
    fun `the line is null when there is no seating at all`() {
        assertNull(SeatAssignment().line)
        assertNull(SeatAssignment(seat = "   ").line)
    }

    @Test
    fun `the short form runs row and seat together like a printed ticket`() {
        assertEquals("F12", SeatAssignment(auditorium = "7", seatRow = "F", seats = listOf("12")).short)
        assertEquals("F12, F13", SeatAssignment(seatRow = "F", seats = listOf("12", "13")).short)
    }

    @Test
    fun `the short form degrades to whatever it has`() {
        assertEquals("F", SeatAssignment(seatRow = "F").short)
        assertEquals("Theatre 7", SeatAssignment(auditorium = "7").short)
        assertEquals("H12", SeatAssignment(seat = "H12").short)
    }

    @Test
    fun `seat entry accepts commas, spaces, or both`() {
        assertEquals(listOf("12", "13"), SeatAssignment.parseSeats("12, 13"))
        assertEquals(listOf("12", "13"), SeatAssignment.parseSeats("12 13"))
        assertEquals(listOf("12", "13"), SeatAssignment.parseSeats("12,13"))
        assertEquals(emptyList<String>(), SeatAssignment.parseSeats("  ,  "))
    }
}
