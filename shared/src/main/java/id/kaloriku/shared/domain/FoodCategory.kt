package id.kaloriku.shared.domain

import id.kaloriku.shared.ai.JevAnswer

/**
 * A coarse food category used to pick a category-appropriate calorie rubric.
 * Indonesian local foods are grouped so Jev can estimate portions sensibly.
 */
enum class FoodCategory(val label: String) {
    NASI("Nasi & Karbohidrat"),
    LAUK_HEWANI("Lauk Hewani"),
    LAUK_NABATI("Lauk Nabati"),
    SAYUR("Sayur"),
    GORENGAN("Gorengan"),
    KUAH("Berkuah"),
    MINUMAN("Minuman"),
    JAJANAN("Jajanan & Manis"),
    BUAH("Buah"),
    LAINNYA("Lainnya"),
    ;

    companion object {
        fun fromKey(key: String?): FoodCategory =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: LAINNYA
    }
}

/**
 * Calorie rubrics: ordered low-to-high kcal buckets for a typical single portion.
 * Jev returns a score index into [buckets]; the app converts that to point/low/high.
 */
object CalorieRubric {

    /** Inclusive kcal ranges, ascending, used as Jev score criteria. */
    val buckets: Map<FoodCategory, List<IntRange>> = mapOf(
        FoodCategory.NASI to listOf(
            0..80, 80..150, 150..250, 250..350, 350..500, 500..700, 700..1000, 1000..1500,
        ),
        FoodCategory.LAUK_HEWANI to listOf(
            0..60, 60..120, 120..200, 200..300, 300..450, 450..650, 650..900, 900..1300,
        ),
        FoodCategory.LAUK_NABATI to listOf(
            0..40, 40..80, 80..130, 130..200, 200..300, 300..450, 450..650, 650..900,
        ),
        FoodCategory.SAYUR to listOf(
            0..25, 25..50, 50..90, 90..150, 150..250, 250..400, 400..600, 600..900,
        ),
        FoodCategory.GORENGAN to listOf(
            0..50, 50..90, 90..140, 140..200, 200..280, 280..400, 400..600, 600..900,
        ),
        FoodCategory.KUAH to listOf(
            0..60, 60..120, 120..200, 200..300, 300..450, 450..650, 650..900, 900..1300,
        ),
        FoodCategory.MINUMAN to listOf(
            0..20, 20..60, 60..120, 120..200, 200..320, 320..500, 500..750, 750..1100,
        ),
        FoodCategory.JAJANAN to listOf(
            0..60, 60..120, 120..200, 200..300, 300..450, 450..650, 650..900, 900..1300,
        ),
        FoodCategory.BUAH to listOf(
            0..30, 30..60, 60..100, 100..160, 160..240, 240..360, 360..550, 550..850,
        ),
        FoodCategory.LAINNYA to listOf(
            0..50, 50..100, 100..180, 180..300, 300..450, 450..700, 700..1000, 1000..1500,
        ),
    )

    /** The Jev score criteria strings for a category, e.g. "80-150 kkal". */
    fun criteriaFor(category: FoodCategory): List<String> {
        val ranges = buckets.getValue(category)
        return ranges.mapIndexed { index, range ->
            val low = range.first
            val high = range.last
            if (index == 0) "di bawah $high kkal"
            else "$low-$high kkal"
        }
    }

    /** Convert a Jev score answer into a point estimate plus low/high bounds. */
    fun interpret(answer: JevAnswer, category: FoodCategory): Triple<Int, Int, Int> {
        val ranges = buckets.getValue(category)
        val last = ranges.size - 1
        val idx = (answer.weightedIndex() ?: 0.0).coerceIn(0.0, last.toDouble())
        val lo = idx.toInt().coerceIn(0, last)
        val hi = (lo + 1).coerceAtMost(last)
        val frac = idx - lo
        val midLo = (ranges[lo].first + ranges[lo].last) / 2.0
        val midHi = (ranges[hi].first + ranges[hi].last) / 2.0
        val point = (midLo + frac * (midHi - midLo)).toInt()
        // Bounds widen by one bucket either side to stay honest about uncertainty.
        val lowBound = ranges[(lo - 1).coerceAtLeast(0)].first
        val highBound = ranges[(lo + 1).coerceAtMost(last)].last
        return Triple(point.coerceAtLeast(0), lowBound, highBound)
    }
}
