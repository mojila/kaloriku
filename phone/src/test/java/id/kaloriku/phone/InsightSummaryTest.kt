package id.kaloriku.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure aggregation behind the "Wawasan" summary.
 *
 * These numbers are what the user actually reads, so the rules that matter are the ones
 * a careless implementation gets wrong: an unlogged day must not be averaged in as a
 * zero, and a "best day" must be the day closest to target in either direction rather
 * than simply the lowest.
 */
class InsightSummaryTest {

    private fun day(dayKey: String, kcal: Int, entries: Int = 1) =
        DayRow(dayKey = dayKey, kcal = kcal, entries = entries, target = 2000)

    private fun summary(days: List<DayRow>, target: Int = 2000) =
        buildInsightSummary(target = target, days = days, healthScore = 3.0, localShare = 0.5)

    @Test
    fun `days with no entries are excluded from the average`() {
        // 3000 and 1000 average to 2000. If the unlogged day were counted as a zero the
        // average would collapse to 1333, which would misread a gap as a crash.
        val result = summary(
            listOf(day("2026-09-23", 3000), day("2026-09-24", 0, entries = 0), day("2026-09-25", 1000)),
        )

        assertEquals(2, result.loggedDays)
        assertEquals(3, result.totalDays)
        assertEquals(2000, result.avgKcal)
    }

    @Test
    fun `an empty history reports no data instead of zeroes`() {
        val result = summary(listOf(day("2026-09-25", 0, entries = 0)))

        assertFalse(result.hasData)
        assertEquals(0, result.avgKcal)
        assertEquals(0.0, result.overTargetShare, 0.0001)
    }

    @Test
    fun `best day is the one closest to target, not the lowest`() {
        // 1800 is 200 under target; 900 is 1100 under. The lowest day is the worse day.
        val result = summary(
            listOf(day("2026-09-23", 900), day("2026-09-24", 1800), day("2026-09-25", 2600)),
        )

        assertEquals("2026-09-24", result.bestDay?.dayKey)
        assertEquals("2026-09-25", result.highestDay?.dayKey)
    }

    @Test
    fun `streak counts consecutive logged days from the most recent`() {
        val result = summary(
            listOf(
                day("2026-09-22", 2000),
                day("2026-09-23", 0, entries = 0),
                day("2026-09-24", 2100),
                day("2026-09-25", 1900),
            ),
        )

        assertEquals(2, result.streak)
    }

    @Test
    fun `over-target accounting counts only logged days`() {
        val result = summary(
            listOf(
                day("2026-09-23", 2500),
                day("2026-09-24", 0, entries = 0),
                day("2026-09-25", 1000),
            ),
        )

        assertEquals(1, result.overTargetDays)
        assertEquals(2, result.loggedDays)
        assertEquals(0.5, result.overTargetShare, 0.0001)
        // (2500 + 1000) / 2 = 1750, so 250 under target. Counting the unlogged day as a
        // zero would give 1166 and report a far larger deficit than the user actually ran.
        assertEquals(-250, result.avgDeltaKcal)
    }

    @Test
    fun `day row reports its delta against the target`() {
        val over = day("2026-09-25", 2500)
        val under = day("2026-09-25", 1500)

        assertTrue(over.isOver)
        assertEquals(500, over.deltaKcal)
        assertFalse(under.isOver)
        assertEquals(-500, under.deltaKcal)
    }

    @Test
    fun `headline never divides by zero and always says something`() {
        // Every branch must produce non-blank copy, including the empty case.
        val empty = summary(listOf(day("2026-09-25", 0, entries = 0)))
        assertTrue(empty.headline.isNotBlank())

        val onTarget = summary(listOf(day("2026-09-25", 2000)))
        assertTrue(onTarget.headline.isNotBlank())

        val over = summary(List(7) { day("2026-09-2$it", 3000) })
        assertTrue(over.headline.isNotBlank())
    }
}
