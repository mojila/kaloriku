package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.domain.DailySummary
import id.kaloriku.shared.domain.DayTotal
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsGeneratorTest {

    private val days = listOf(
        DayTotal("2026-09-23", 2100, 5),
        DayTotal("2026-09-24", 1800, 4),
        DayTotal("2026-09-25", 2400, 6),
    )

    private val summaries = listOf(
        DailySummary("2026-09-23", 2100, 5, mapOf(MealType.SARAPAN to 400), 1500, 2.5),
        DailySummary("2026-09-24", 1800, 4, mapOf(MealType.SARAPAN to 300), 1200, 3.0),
        DailySummary("2026-09-25", 2400, 6, mapOf(MealType.SARAPAN to 500), 1600, 2.0),
    )

    @Test
    fun `uses the chat model text when it succeeds`() = runTest {
        val api = FakeKenariApi()
            .enqueueJev(
                JevResponse(
                    model = "jev-1.13-free",
                    answers = mapOf("focus" to FakeKenariApi.choiceAnswer("kurangi_porsi")),
                ),
            )
            .enqueueChat("Pola makanmu cukup, tapi kalori sedikit tinggi. Coba kurangi porsi nasi.")
        val generator = InsightsGenerator(api)
        val insight = generator.generate(2000, days, summaries)
        assertEquals("kurangi_porsi", insight.source)
        assertTrue(insight.text.contains("kurangi"))
        assertTrue(insight.healthTrend > 2.0)
    }

    @Test
    fun `falls back to a local summary when chat fails`() = runTest {
        val api = FakeKenariApi()
        api.jevThrows = id.kaloriku.shared.ai.KenariException("jev down")
        api.chatThrows = id.kaloriku.shared.ai.KenariException("chat down")
        val generator = InsightsGenerator(api)
        val insight = generator.generate(2000, days, summaries)
        assertTrue(insight.text.contains("target"))
        assertTrue(insight.localShare > 0.5)
    }

    @Test
    fun `handles empty data without dividing by zero`() = runTest {
        val api = FakeKenariApi()
        api.jevThrows = id.kaloriku.shared.ai.KenariException("no data")
        api.chatThrows = id.kaloriku.shared.ai.KenariException("no data")
        val generator = InsightsGenerator(api)
        val insight = generator.generate(2000, emptyList(), emptyList())
        assertEquals(0.0, insight.localShare, 0.0001)
        assertTrue(insight.text.isNotBlank())
    }

    @Test
    fun `strips conversational preamble from the model output`() = runTest {
        val api = FakeKenariApi()
            .enqueueJev(JevResponse(model = "jev-1.13-free", answers = emptyMap()))
            .enqueueChat(
                "Tentu, ini analisis singkat berdasarkan data yang Anda berikan:\n\n" +
                    "Pola makanmu sudah cukup baik minggu ini.",
            )
        val generator = InsightsGenerator(api)
        val insight = generator.generate(2000, days, summaries)
        assertTrue("no preamble", insight.text.startsWith("Pola makanmu"))
    }
}
