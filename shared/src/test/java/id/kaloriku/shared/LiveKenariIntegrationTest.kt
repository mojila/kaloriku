package id.kaloriku.shared

import id.kaloriku.shared.ai.KenariClient
import id.kaloriku.shared.analysis.FoodAnalyzer
import id.kaloriku.shared.domain.LogSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in integration test that hits the real Kenari gateway. It is skipped unless
 * KENARI_API_KEY is present, so normal builds stay offline and deterministic.
 */
class LiveKenariIntegrationTest {

    private val apiKey: String? = System.getenv("KENARI_API_KEY")

    @Test
    fun `analyzes an Indonesian utterance end to end`() {
        assumeTrue("KENARI_API_KEY not set; skipping live test", !apiKey.isNullOrBlank())
        val client = KenariClient(apiKeyProvider = { apiKey!! })
        val analyzer = FoodAnalyzer(client)

        val result = runBlocking {
            analyzer.analyze(
                "tadi siang aku makan nasi padang dengan rendang dan daun singkong, " +
                    "terus minum es teh manis satu gelas",
                LogSource.VOICE_PHONE,
            )
        }

        println("LIVE transcript: ${result.transcript}")
        println("LIVE normalized: ${result.normalized}")
        println("LIVE meal: ${result.meal} health=${result.healthScore} engine=${result.engine}")
        result.items.forEach {
            println("LIVE item: ${it.name} | ${it.portionText} | ${it.kcal} kkal (${it.kcalLow}-${it.kcalHigh}) local=${it.isLocal}")
        }
        println("LIVE total: ${result.totalKcal} kkal (${result.totalLow}-${result.totalHigh})")

        assertTrue("expected at least one item", result.items.isNotEmpty())
        assertTrue("total should be positive", result.totalKcal > 0)
        assertTrue("should detect local Indonesian food", result.items.any { it.isLocal })
    }

    /**
     * Guards against Jev drifting far from the curated catalog. The estimate must
     * stay within the same order of magnitude as the reference portion.
     */
    @Test
    fun `known local foods stay near their catalog reference`() {
        assumeTrue("KENARI_API_KEY not set; skipping live test", !apiKey.isNullOrBlank())
        val client = KenariClient(apiKeyProvider = { apiKey!! })
        val analyzer = FoodAnalyzer(client)

        val cases = listOf(
            "nasi goreng satu piring" to "nasi_goreng",
            "sate ayam sepuluh tusuk" to "sate_ayam",
            "es teh manis satu gelas" to "es_teh_manis",
            "tempe goreng dua potong" to "tempe_goreng",
        )

        cases.forEach { (utterance, expectedId) ->
            val result = runBlocking { analyzer.analyze(utterance, LogSource.VOICE_PHONE) }
            val item = result.items.firstOrNull { it.canonicalName == expectedId }
            val reference = id.kaloriku.shared.domain.LocalFoodCatalog.byId(expectedId)!!.kcalPerPortion
            println(
                "LIVE anchor: $utterance -> " +
                    result.items.joinToString { "${it.name}[${it.canonicalName}]=${it.kcal}" } +
                    " (acuan $expectedId=$reference)",
            )
            assertTrue("$utterance did not match $expectedId", item != null)
            val ratio = item!!.kcal.toDouble() / reference
            assertTrue(
                "$utterance estimate ${item.kcal} is far from reference $reference (ratio $ratio)",
                ratio in 0.4..2.5,
            )
        }
    }

    /**
     * The branded-food path: "Burger Ayam Dikichi" is not in the catalog, so the
     * pipeline must ground it with Kenari web search and still produce a plausible
     * burger-sized estimate from Jev.
     */
    @Test
    fun `an unknown branded food is grounded via web search`() {
        assumeTrue("KENARI_API_KEY not set; skipping live test", !apiKey.isNullOrBlank())
        val client = KenariClient(apiKeyProvider = { apiKey!! })
        val analyzer = FoodAnalyzer(client)

        val result = runBlocking {
            analyzer.analyze("tadi siang aku makan burger ayam dikichi", LogSource.VOICE_PHONE)
        }

        println("LIVE branded transcript: ${result.transcript}")
        println("LIVE branded normalized: ${result.normalized}")
        println("LIVE branded engine: ${result.engine}")
        result.items.forEach {
            println(
                "LIVE branded item: ${it.name} | ${it.portionText} | " +
                    "${it.kcal} kkal (${it.kcalLow}-${it.kcalHigh}) local=${it.isLocal}",
            )
        }

        assertTrue("expected one item", result.items.isNotEmpty())
        assertTrue("a calorie figure must be produced", result.totalKcal > 0)
        assertTrue(
            "a burger is not a small snack: got ${result.totalKcal} kkal",
            result.totalKcal >= 250,
        )
    }
}
