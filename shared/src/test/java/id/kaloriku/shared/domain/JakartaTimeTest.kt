package id.kaloriku.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

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
}
