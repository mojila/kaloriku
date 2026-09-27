package id.kaloriku.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class JakartaTimeTest {

    @Test
    fun `day key uses the Jakarta calendar day`() {
        // 2026-09-25 00:30 WIB is still 2026-09-25 even though UTC is 24 Sep.
        val millis = java.time.ZonedDateTime.of(2026, 9, 25, 0, 30, 0, 0, ZoneId.of("Asia/Jakarta"))
            .toInstant().toEpochMilli()
        assertEquals("2026-09-25", JakartaTime.dayKey(millis))
    }

    @Test
    fun `start and end of day bracket the day`() {
        val start = JakartaTime.startOfDayMillis("2026-09-25")
        val end = JakartaTime.endOfDayMillis("2026-09-25")
        assertTrue(start < end)
        assertEquals("2026-09-25", JakartaTime.dayKey(start))
        assertEquals("2026-09-25", JakartaTime.dayKey(end))
    }

    @Test
    fun `last day keys are ordered oldest first and end at the given day`() {
        val keys = JakartaTime.lastDayKeys(3, "2026-09-25")
        assertEquals(listOf("2026-09-23", "2026-09-24", "2026-09-25"), keys)
    }

    @Test
    fun `label marks today and yesterday in Indonesian`() {
        assertEquals("Hari ini", JakartaTime.label("2026-09-25", today = "2026-09-25"))
        assertEquals("Kemarin", JakartaTime.label("2026-09-24", today = "2026-09-25"))
    }

    @Test
    fun `hour of day is in Jakarta time`() {
        val millis = java.time.ZonedDateTime.of(2026, 9, 25, 20, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
            .toInstant().toEpochMilli()
        assertEquals(20, JakartaTime.hourOfDay(millis))
    }

    // ------------------------------------------- date/time editing (reschedule) helpers

    @Test
    fun `atTime builds the Jakarta instant for a day and wall-clock time`() {
        val expected = ZonedDateTime
            .of(2026, 9, 25, 14, 45, 0, 0, ZoneId.of("Asia/Jakarta"))
            .toInstant().toEpochMilli()

        val millis = JakartaTime.atTime("2026-09-25", 14, 45)

        assertEquals(expected, millis)
        // Same instant read back through the day-key helper must round-trip the day.
        assertEquals("2026-09-25", JakartaTime.dayKey(millis))
        assertEquals(14, JakartaTime.hourOfDay(millis))
        assertEquals(45, JakartaTime.minuteOfHour(millis))
    }

    @Test
    fun `atTime at midnight is the start of the Jakarta day`() {
        assertEquals(
            JakartaTime.startOfDayMillis("2026-09-25"),
            JakartaTime.atTime("2026-09-25", 0, 0),
        )
    }

    @Test
    fun `atTime round-trips every hour and minute of a day`() {
        for (hour in 0..23) {
            for (minute in intArrayOf(0, 1, 30, 59)) {
                val millis = JakartaTime.atTime("2026-09-25", hour, minute)
                assertEquals("day for $hour:$minute", "2026-09-25", JakartaTime.dayKey(millis))
                assertEquals("hour for $hour:$minute", hour, JakartaTime.hourOfDay(millis))
                assertEquals("minute for $hour:$minute", minute, JakartaTime.minuteOfHour(millis))
            }
        }
    }

    @Test
    fun `atTime clamps out-of-range hour and minute instead of throwing`() {
        val midnight = JakartaTime.atTime("2026-09-25", 0, 0)
        val lastMinute = JakartaTime.atTime("2026-09-25", 23, 59)

        // Out-of-range values are clamped into [0,23] / [0,59], so a bad picker value
        // degrades to the nearest valid wall-clock time rather than crashing the flow.
        assertEquals(midnight, JakartaTime.atTime("2026-09-25", -5, -10))
        assertEquals(lastMinute, JakartaTime.atTime("2026-09-25", 25, 90))
        assertEquals("2026-09-25", JakartaTime.dayKey(JakartaTime.atTime("2026-09-25", 24, 0)))
    }

    @Test
    fun `picker millis and day key round-trip without a shift`() {
        for (day in listOf("2026-09-25", "2026-01-01", "2026-12-31", "2028-02-29")) {
            assertEquals(day, JakartaTime.dayKeyFromPickerUtc(JakartaTime.pickerUtcMillis(day)))
            assertEquals(day, JakartaTime.dayKey(JakartaTime.pickerUtcMillis(day)))
        }
    }

    @Test
    fun `picker utc millis is exactly utc midnight of the day`() {
        // Material's DatePickerState.selectedDateMillis is midnight UTC of the chosen
        // calendar date, not a Jakarta instant.
        val utcMidnight = ZonedDateTime
            .of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        assertEquals(utcMidnight, JakartaTime.pickerUtcMillis("2026-09-25"))
        // Jakarta local midnight of the 25th is an hour-preceding instant (17:00 UTC
        // on the 24th), so the two must NOT be confused.
        assertNotEquals(JakartaTime.startOfDayMillis("2026-09-25"), JakartaTime.pickerUtcMillis("2026-09-25"))
    }

    @Test
    fun `the picker trap is the reverse direction, not the utc-midnight read`() {
        // Asia/Jakarta is UTC+7, so UTC midnight of a day always lands at 07:00 WIB on
        // the SAME calendar day. Reading the picker value with dayKey therefore does NOT
        // shift for Jakarta.
        assertEquals("2026-09-25", JakartaTime.dayKeyFromPickerUtc(JakartaTime.pickerUtcMillis("2026-09-25")))
        assertEquals("2026-09-25", JakartaTime.dayKey(JakartaTime.pickerUtcMillis("2026-09-25")))

        // The real trap runs the other way: a Jakarta *local-midnight* instant fed to
        // the UTC picker reader shifts back a day, which is why the picker must never
        // be initialised from startOfDayMillis.
        assertEquals(
            "a local-midnight instant read as UTC picker value shifts back a day",
            "2026-09-24",
            JakartaTime.dayKeyFromPickerUtc(JakartaTime.startOfDayMillis("2026-09-25")),
        )
    }

    @Test
    fun `minute of hour is in Jakarta time`() {
        val millis = JakartaTime.atTime("2026-09-25", 7, 5)
        assertEquals(5, JakartaTime.minuteOfHour(millis))
        assertEquals(0, JakartaTime.minuteOfHour(JakartaTime.atTime("2026-09-25", 7, 0)))
        assertEquals(59, JakartaTime.minuteOfHour(JakartaTime.atTime("2026-09-25", 7, 59)))
    }

    @Test
    fun `full date renders a non-empty Indonesian date and never collapses to today`() {
        val text = JakartaTime.fullDate("2026-09-25")
        assertTrue("fullDate must not be empty", text.isNotBlank())
        assertTrue("fullDate must carry the year", text.contains("2026"))
        assertTrue("fullDate must carry the day", text.contains("25"))
        assertNotEquals("Hari ini", text)
        assertNotEquals("Kemarin", text)
        // The label helper still collapses relative days; fullDate is the escape hatch
        // the reschedule UI uses to show the exact target date.
        assertEquals("Hari ini", JakartaTime.label("2026-09-25", today = "2026-09-25"))
    }
}
