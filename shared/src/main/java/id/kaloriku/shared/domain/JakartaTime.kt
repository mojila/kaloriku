package id.kaloriku.shared.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date/time helpers pinned to Asia/Jakarta, the app's home timezone. */
object JakartaTime {
    val zone: ZoneId = ZoneId.of("Asia/Jakarta")
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun dayKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dayFormatter)

    fun todayKey(nowMillis: Long = System.currentTimeMillis()): String = dayKey(nowMillis)

    fun startOfDayMillis(dayKey: String): Long =
        LocalDate.parse(dayKey, dayFormatter).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMillis(dayKey: String): Long =
        LocalDate.parse(dayKey, dayFormatter).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    /** Recent day keys, oldest first, ending at [endDay]. */
    fun lastDayKeys(days: Int, endDay: String = todayKey()): List<String> {
        val end = LocalDate.parse(endDay, dayFormatter)
        return (days - 1 downTo 0).map { end.minusDays(it.toLong()).format(dayFormatter) }
    }

    fun hourOfDay(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    fun minuteOfHour(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).minute

    /**
     * Combines a day key with a wall-clock time into an instant.
     *
     * Used when the user edits the date/time of a logged entry: the picker gives a day
     * and an hour/minute, and this turns the pair back into the epoch millis the entry
     * is stored under. Asia/Jakarta has no DST, so the local time is never ambiguous.
     */
    fun atTime(dayKey: String, hour: Int, minute: Int): Long =
        LocalDate.parse(dayKey, dayFormatter)
            .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * Converts a Material `DatePicker` selection into a day key.
     *
     * `DatePickerState.selectedDateMillis` is midnight **UTC** of the chosen calendar
     * date, not a local instant. For Asia/Jakarta (UTC+7) reading that value in [zone]
     * happens to land at 07:00 on the same day, so it is easy to get away with — but the
     * two are different kinds of value, and the mistake becomes a real off-by-one day
     * whenever the zone offset is negative or the picker value is a local midnight.
     * Keeping the picker on the UTC side and the app on the Jakarta side is what makes
     * the conversion correct rather than accidentally correct.
     */
    fun dayKeyFromPickerUtc(utcMidnightMillis: Long): String =
        Instant.ofEpochMilli(utcMidnightMillis).atZone(java.time.ZoneOffset.UTC)
            .toLocalDate().format(dayFormatter)

    /**
     * The inverse of [dayKeyFromPickerUtc]: the UTC-midnight millis a `DatePicker`
     * expects as its initial selection for [dayKey]. Pairing the two keeps the picker
     * round-tripping the user's calendar date without a timezone shift.
     */
    fun pickerUtcMillis(dayKey: String): Long =
        LocalDate.parse(dayKey, dayFormatter)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    /** Human label for a day key relative to today, in Indonesian. */
    fun label(dayKey: String, today: String = todayKey()): String = when (dayKey) {
        today -> "Hari ini"
        LocalDate.parse(today, dayFormatter).minusDays(1).format(dayFormatter) -> "Kemarin"
        else -> runCatching {
            LocalDate.parse(dayKey, dayFormatter)
                .format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("id-ID")))
        }.getOrDefault(dayKey)
    }

    /**
     * Full Indonesian date for a day key, e.g. "25 Sep 2026".
     *
     * Unlike [label] this never collapses to "Hari ini"/"Kemarin", so the date-editing
     * UI can always show exactly which calendar day an entry will be moved to.
     */
    fun fullDate(dayKey: String): String = runCatching {
        LocalDate.parse(dayKey, dayFormatter)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("id-ID")))
    }.getOrDefault(dayKey)
}
