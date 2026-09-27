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

    /** Human label for a day key relative to today, in Indonesian. */
    fun label(dayKey: String, today: String = todayKey()): String = when (dayKey) {
        today -> "Hari ini"
        LocalDate.parse(today, dayFormatter).minusDays(1).format(dayFormatter) -> "Kemarin"
        else -> runCatching {
            LocalDate.parse(dayKey, dayFormatter)
                .format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale.forLanguageTag("id-ID")))
        }.getOrDefault(dayKey)
    }
}
