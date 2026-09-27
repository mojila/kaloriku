package id.kaloriku.shared.analysis

import id.kaloriku.shared.ai.JevQuestion
import id.kaloriku.shared.ai.KenariApi
import id.kaloriku.shared.domain.DailySummary
import id.kaloriku.shared.domain.DayTotal
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A generated Indonesian insight for the phone app's "Wawasan" screen. */
data class Insight(
    val text: String,
    val healthTrend: Double,
    val localShare: Double,
    val source: String,
)

/**
 * Produces the phone app's analysis: a Jev health-score trend plus a natural
 * language summary from the chat model grounded in the user's actual numbers.
 */
class InsightsGenerator(private val api: KenariApi) {

    suspend fun generate(
        target: Int,
        days: List<DayTotal>,
        summaries: List<DailySummary>,
    ): Insight = withContext(Dispatchers.Default) {
        val trend = if (summaries.isEmpty()) 0.0 else summaries.map { it.avgHealthScore }.average()
        val localShare = summaries.let { list ->
            val total = list.sumOf { it.totalKcal }
            if (total == 0) 0.0 else list.sumOf { it.localKcal }.toDouble() / total
        }

        val facts = buildString {
            appendLine("Target kalori harian: $target kkal.")
            appendLine("Data harian (tanggal: total kkal, jumlah catatan):")
            days.forEach { appendLine("- ${JakartaTime.label(it.dayKey)}: ${it.kcal} kkal, ${it.entries} catatan") }
            if (summaries.isNotEmpty()) {
                val avg = summaries.map { it.totalKcal }.average().toInt()
                appendLine("Rata-rata kalori harian: $avg kkal.")
                appendLine("Rata-rata skor kesehatan (0-4): ${"%.2f".format(trend)}.")
                appendLine("Proporsi kalori dari makanan lokal Indonesia: ${(localShare * 100).toInt()}%.")
                val mealTotals = MealType.entries.associateWith { meal ->
                    summaries.sumOf { it.byMeal[meal] ?: 0 }
                }
                appendLine("Total per waktu makan: " + mealTotals.entries.joinToString(", ") { "${it.key.label}=${it.value}" } + ".")
            } else {
                appendLine("Belum ada data.")
            }
        }

        val insightQuestion = JevQuestion.Choice(
            instructions =
            "Berdasarkan state, pilih satu fokus perbaikan yang paling penting untuk pengguna.",
            options = linkedMapOf(
                "kurangi_porsi" to "porsi atau total kalori berlebihan",
                "lebih_seimbang" to "kurang seimbang gizinya",
                "kurang_makan" to "asupan terlalu rendah",
                "lebih_lokal" to "perbanyak makanan lokal bergizi",
                "pertahankan" to "pola sudah baik, pertahankan",
                "tambah_data" to "data belum cukup untuk menilai",
            ),
        )

        val focus = runCatching {
            api.jev(facts, mapOf("focus" to insightQuestion)).answers["focus"]?.choice
        }.getOrNull()

        val text = runCatching {
            api.chat(
                Prompts.INSIGHT,
                facts + "\nFokus perbaikan yang dipilih: ${focus ?: "tidak diketahui"}.",
                jsonMode = false,
            )
        }.getOrElse { localSummary(target, days, trend, localShare, focus) }

        Insight(
            text = stripPreamble(text),
            healthTrend = trend,
            localShare = localShare,
            source = focus ?: "",
        )
    }

    /** Remove the conversational lead-ins models tend to prepend. */
    private fun stripPreamble(raw: String): String {
        var text = raw.trim()

        // If the first paragraph is a lead-in ("Tentu, ini analisis...") drop it.
        val paragraphs = text.split(Regex("\\n\\s*\\n"), limit = 2)
        if (paragraphs.size == 2) {
            val first = paragraphs[0].lowercase()
            val looksLikePreamble = listOf(
                "tentu", "baik", "berikut", "inilah", "ini analisis",
                "berdasarkan data", "analisis singkat", "ringkasan",
            ).any { first.contains(it) } && paragraphs[0].length < 220
            if (looksLikePreamble) text = paragraphs[1].trim()
        }

        val patterns = listOf(
            Regex("^(tentu|baik|oke|ok|sure|certainly)[,!.]?\\s*", RegexOption.IGNORE_CASE),
            Regex("^(berikut|ini|inilah)[^.!:\\n]{0,90}[:.!]\\s*", RegexOption.IGNORE_CASE),
        )
        var changed = true
        while (changed) {
            changed = false
            for (pattern in patterns) {
                val next = text.replaceFirst(pattern, "")
                if (next != text) {
                    text = next.trimStart()
                    changed = true
                }
            }
        }
        return text.trim()
    }

    private fun localSummary(
        target: Int,
        days: List<DayTotal>,
        trend: Double,
        localShare: Double,
        focus: String?,
    ): String {
        val avg = if (days.isEmpty()) 0 else days.map { it.kcal }.average().toInt()
        val gap = avg - target
        val trendText = when {
            trend >= 3.5 -> "pola makanmu sudah cukup sehat."
            trend >= 2.5 -> "pola makanmu cukup, tapi bisa lebih seimbang."
            else -> "pola makanmu perlu perbaikan, terutama keseimbangan gizinya."
        }
        val gapText = when {
            gap > 300 -> "Rata-rata $avg kkal per hari, sekitar $gap kkal di atas target $target kkal."
            gap < -300 -> "Rata-rata $avg kkal per hari, sekitar ${-gap} kkal di bawah target $target kkal."
            else -> "Rata-rata $avg kkal per hari, sudah dekat dengan target $target kkal."
        }
        val localText = "Sekitar ${(localShare * 100).toInt()}% kalori berasal dari makanan lokal Indonesia."
        val advice = when (focus) {
            "kurangi_porsi" -> "Coba kurangi porsi nasi dan gorengan, ganti camilan dengan buah lokal seperti pepaya atau jeruk."
            "lebih_seimbang" -> "Tambahkan sayur seperti kangkung tumis dan lauk berprotein seperti tahu, tempe, atau ikan."
            "kurang_makan" -> "Pastikan makan cukup; tambahkan nasi, lauk berprotein, dan buah agar energi tercukupi."
            "lebih_lokal" -> "Perbanyak makanan lokal bergizi seperti gado-gado, pecel, atau soto ayam."
            "pertahankan" -> "Pertahankan pola ini dan tetap catat setiap makan agar konsisten."
            else -> "Terus catat makananmu agar analisis makin akurat."
        }
        return "$gapText $trendText $localText $advice"
    }
}
