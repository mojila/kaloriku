package id.kaloriku.shared.analysis

import id.kaloriku.shared.ai.JevAnswer
import id.kaloriku.shared.ai.JevQuestion
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.KenariApi
import id.kaloriku.shared.ai.KenariException
import id.kaloriku.shared.domain.AnalysisResult
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.CalorieRubric
import id.kaloriku.shared.domain.FoodCategory
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LocalFoodCatalog
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Turns an Indonesian utterance into a structured, Jev-validated calorie estimate.
 *
 * Pipeline:
 *  1. Chat model (`deepseek-v4-1-flash`) normalises the transcript and extracts
 *     food items, portions and the meal time as JSON.
 *  2. Each item is matched against the local Indonesian catalog to get a category.
 *  3. Jev (`jev-1-13-free`, System One) answers typed questions per item: calorie
 *     bucket, portion size, macro profile, local-food check and ambiguity check.
 *     A global set asks for the meal type and a health score.
 *  4. Jev is asked whether each item names a brand, outlet or restaurant. A branded
 *     item ("Burger Ayam Dikichi", "Ayam Geprek Bensu", "Indomie Goreng") is grounded
 *     with Kenari web search even when Jev would otherwise call it local or clear,
 *     because the brand is what determines the calories. Items that are merely unknown
 *     are grounded too. The evidence is summarised and fed back to Jev before the
 *     calorie question is answered again. Jev stays the decision engine; the web only
 *     supplies facts.
 *  5. Answers are converted into a point estimate with an honest low/high range.
 */
class FoodAnalyzer(
    private val api: KenariApi,
    private val catalogSearch: (String) -> List<id.kaloriku.shared.domain.LocalFood> = { LocalFoodCatalog.search(it) },
    private val catalogBest: (String) -> id.kaloriku.shared.domain.LocalFood? = { LocalFoodCatalog.bestMatch(it) },
) {

    private val maxItems = 8

    /**
     * How many web lookups one utterance may trigger. Search is billed per call, and
     * an utterance almost never contains more than a couple of truly unknown foods,
     * so the rest keep their Jev estimate from the catalog-free first pass.
     */
    private val maxWebLookups = 3

    suspend fun analyze(
        transcript: String,
        source: LogSource,
        nowMillis: Long = System.currentTimeMillis(),
        existingRecent: List<FoodEntry> = emptyList(),
    ): AnalysisResult = withContext(Dispatchers.Default) {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            return@withContext emptyResult("", nowMillis = nowMillis)
        }

        val extraction = runCatching { extract(cleaned, nowMillis) }.getOrElse { fallbackExtraction(cleaned, nowMillis) }
        val items = extraction.items.take(maxItems)
        if (items.isEmpty()) {
            return@withContext emptyResult(cleaned, extraction.normalized, nowMillis)
        }

        var resolved = items.map { item ->
            resolveItem(item)
        }

        val questions = buildQuestions(resolved, extraction.mealHint, cleaned, existingRecent, nowMillis)
        // Jev is the decision engine: if it cannot be reached, the result is flagged as
        // degraded rather than presented as a Jev judgement.
        var degraded = false
        var jev = runCatching { api.jev(buildState(cleaned, extraction, resolved, nowMillis), questions) }
            .getOrElse {
                degraded = true
                fallbackJev(resolved, extraction.mealHint, nowMillis)
            }

        // Ground the items the catalog and Jev could not place with web evidence, then
        // let Jev answer their calorie questions again with those facts in the state.
        val grounded = groundUnrecognized(resolved, jev)
        if (grounded.isNotEmpty()) {
            resolved = resolved.mapIndexed { index, item ->
                grounded[index] ?: item
            }
            val reask = buildItemQuestions(
                grounded.map { (index, item) -> index to item },
                // Only the calorie question is re-asked with the web evidence: re-asking
                // brand/local/clear would let a web snippet rewrite Jev's classification,
                // and the portion scale did not change.
                calorieOnly = true,
            )
            val second = runCatching {
                api.jev(buildState(cleaned, extraction, resolved, nowMillis), reask)
            }.getOrNull()
            if (second != null) jev = jev.merge(second)
        }

        val analyzed = resolved.mapIndexed { index, resolvedItem ->
            toAnalyzedItem(index, resolvedItem, jev)
        }

        val meal = resolveMeal(jev, extraction.mealHint, nowMillis)
        val health = jev.answers["health"]?.score ?: 3.0
        val needsClarification = degraded || analyzed.any { it.needsClarification }
        val duplicate = detectDuplicate(jev, existingRecent)

        val total = analyzed.sumOf { it.kcal }
        AnalysisResult(
            transcript = cleaned,
            normalized = extraction.normalized,
            items = analyzed,
            meal = meal,
            healthScore = health,
            needsClarification = needsClarification,
            totalKcal = total,
            totalLow = analyzed.sumOf { it.kcalLow },
            totalHigh = analyzed.sumOf { it.kcalHigh },
            isDuplicate = duplicate != null,
            duplicateOfId = duplicate,
            engine = if (degraded) DEGRADED_ENGINE else jev.model,
        )
    }

    /**
     * Re-runs the Jev decision pipeline for a single already-logged item after the
     * user edits its name or portion.
     *
     * Editing is not arithmetic: renaming "Nasi Goreng" to "Bubur Ayam" or changing
     * the portion size changes what should be estimated, so the calorie/portion/local
     * decisions are re-asked rather than scaled. Only the language step is skipped —
     * the name is already a clean food name, so no chat call is needed.
     *
     * Never throws. If Jev is unreachable it falls back to the local catalog estimate
     * for the new name, and to [previous] when the new name is blank, so an edit can
     * never leave an entry without a calorie figure.
     */
    suspend fun reestimate(
        name: String,
        portionText: String,
        grams: Double?,
        meal: MealType,
        previous: AnalyzedItem,
        nowMillis: Long = System.currentTimeMillis(),
    ): AnalyzedItem = withContext(Dispatchers.Default) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return@withContext previous

        val match = resolveItem(ExtractedItem(cleaned, portionText.trim(), grams)).match
        var resolved = ResolvedItem(ExtractedItem(cleaned, portionText.trim(), grams), match)
        val extraction = Extraction(cleaned, listOf(resolved.item), meal)

        val firstQuestions = buildQuestions(
            items = listOf(resolved),
            mealHint = meal,
            transcript = cleaned,
            existingRecent = emptyList(),
            nowMillis = nowMillis,
        )
        var degraded = false
        var jev = runCatching {
            api.jev(buildState(cleaned, extraction, listOf(resolved), nowMillis), firstQuestions)
        }.getOrElse {
            degraded = true
            fallbackJev(listOf(resolved), meal, nowMillis)
        }

        // A rename can introduce a branded food the catalog does not know, so the
        // same web-grounding pass runs here as in the voice pipeline.
        val grounded = groundUnrecognized(listOf(resolved), jev).values.firstOrNull()
        if (grounded != null) {
            resolved = grounded
            val second = runCatching {
                api.jev(
                    buildState(cleaned, extraction, listOf(resolved), nowMillis),
                    buildItemQuestions(listOf(0 to resolved), calorieOnly = true),
                )
            }.getOrNull()
            if (second != null) jev = jev.merge(second)
        }

        val analyzed = toAnalyzedItem(0, resolved, jev)
        analyzed.copy(
            // Preserve identity the user did not touch.
            grams = grams,
            name = cleaned,
            // A degraded (Jev-less) estimate must not look like a Jev judgement.
            needsClarification = degraded || analyzed.needsClarification,
        )
    }

    // ---------------------------------------------------------------- extraction

    private data class ExtractedItem(val name: String, val portion: String, val grams: Double?)
    private data class Extraction(
        val normalized: String,
        val items: List<ExtractedItem>,
        val mealHint: MealType?,
    )

    private suspend fun extract(text: String, nowMillis: Long): Extraction {
        val raw = api.chat(Prompts.EXTRACTION, text, jsonMode = true)
        val json = parseJsonObject(raw)
        val normalized = json.optString("ringkasan").takeIf { it.isNotBlank() } ?: text
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("nama").trim()
                if (name.isEmpty()) continue
                add(
                    ExtractedItem(
                        name = name,
                        portion = o.optString("porsi").trim(),
                        grams = parseGrams(o),
                    ),
                )
            }
        }
        val mealHint = json.optString("waktu").takeIf { it.isNotBlank() }?.let(::mealFromKey)
        if (items.isEmpty()) return fallbackExtraction(text, nowMillis)
        return Extraction(normalized, items, mealHint)
    }

    /**
     * Reads an item's optional weight in grams.
     *
     * `optDouble` answers `NaN` for a value that is not a number — which is exactly what
     * a model returns for "gram": "sekitar 100" — and `JSONObject.put` later throws
     * `JSONException: JSON does not allow non-finite numbers` on a non-finite Double.
     * That would break the sync push and the backup export for an entry the user can
     * see, so a non-finite weight is treated as "not stated" instead of being carried
     * into the persisted model. A negative weight is equally meaningless and is dropped
     * for the same reason.
     */
    private fun parseGrams(item: JSONObject): Double? {
        if (!item.has("gram") || item.isNull("gram")) return null
        val grams = item.optDouble("gram")
        return grams.takeIf { it.isFinite() && it > 0.0 }
    }

    /** No-LLM fallback: split on common Indonesian connectors and match the catalog. */
    private fun fallbackExtraction(text: String, nowMillis: Long): Extraction {
        val lower = text.lowercase()
        val hits = catalogSearch(lower)
        val items = if (hits.isNotEmpty()) {
            hits.distinctBy { it.id }.take(maxItems).map {
                ExtractedItem(it.name, it.portionLabel, null)
            }
        } else {
            text.split(",", " dan ", " sama ", " terus ", " lalu ")
                .map { it.trim() }
                .filter { it.length in 2..60 }
                .take(maxItems)
                .map { ExtractedItem(it, "", null) }
        }
        return Extraction(text, items, mealFromHour(JakartaTime.hourOfDay(nowMillis)))
    }

    // ------------------------------------------------------------------ Jev call

    private data class ResolvedItem(
        val item: ExtractedItem,
        val match: id.kaloriku.shared.domain.LocalFood?,
        /** Web evidence gathered for a branded/unknown food, if any. */
        val evidence: WebEvidence? = null,
    ) {
        val category: FoodCategory get() = match?.category ?: FoodCategory.LAINNYA
    }

    private fun buildQuestions(
        items: List<ResolvedItem>,
        mealHint: MealType?,
        transcript: String,
        existingRecent: List<FoodEntry>,
        nowMillis: Long,
    ): Map<String, JevQuestion> = buildMap {
        put(
            "health",
            JevQuestion.Score(
                instructions =
                "Nilai seberapa sehat pola makan pada state ini untuk orang dewasa Indonesia " +
                    "yang ingin menjaga berat badan. Pertimbangkan keseimbangan gizi, minyak, " +
                    "gula, dan porsi. Skala dari sangat tidak sehat ke sangat sehat.",
                levels = listOf("sangat tidak sehat", "tidak sehat", "cukup", "sehat", "sangat sehat"),
            ),
        )
        put(
            "meal",
            JevQuestion.Choice(
                instructions = "Waktu makan mana yang paling mungkin untuk makanan ini?",
                options = linkedMapOf(
                    "SARAPAN" to "sarapan pagi",
                    "MAKAN_SIANG" to "makan siang",
                    "MAKAN_MALAM" to "makan malam",
                    "CAMILAN" to "camilan atau kudapan di luar waktu makan",
                ),
            ),
        )
        putAll(buildItemQuestions(items.mapIndexed { index, item -> index to item }))
        putAll(buildDuplicateQuestions(existingRecent))
    }

    /**
     * Asks Jev to identify the duplicate among the recent entries, not merely whether
     * one exists: the state lists up to eight candidates, so a single yes/no could only
     * ever point at the newest one and would flag the wrong entry. The options are the
     * candidates themselves; the first option is the "this is new" escape hatch, which
     * keeps a non-duplicate from being forced onto an arbitrary candidate.
     */
    private fun buildDuplicateQuestions(existingRecent: List<FoodEntry>): Map<String, JevQuestion> {
        if (existingRecent.isEmpty()) return emptyMap()
        val candidates = existingRecent.take(8)
        val options = linkedMapOf("BARU" to "ini catatan baru, bukan salinan")
        candidates.forEachIndexed { index, entry ->
            options["$DUP_PREFIX$index"] =
                "${entry.foodName} (${entry.kcal} kkal, ${entry.meal.label})"
        }
        return mapOf(
            "duplicate" to JevQuestion.Choice(
                instructions =
                "Pengguna baru saja mencatat beberapa makanan. Apakah makanan pada state ini " +
                    "kemungkinan salinan dari salah satu catatan terakhir di bawah ini? Pilih " +
                    "catatan yang paling mirip, atau \"BARU\" bila ini catatan tambahan yang baru.",
                options = options,
            ),
        )
    }

    /**
     * The per-item questions, keyed by each item's index in the full item list. Split
     * out from [buildQuestions] so the calorie/portion/local/ambiguity questions can be
     * asked a second time for the items that got web evidence, without re-asking the
     * global meal and health questions. Passing the index explicitly keeps the question
     * names aligned with the item positions in the Jev state when only a subset is re-asked.
     */
    private fun buildItemQuestions(
        indexedItems: List<Pair<Int, ResolvedItem>>,
        calorieOnly: Boolean = false,
    ): Map<String, JevQuestion> = buildMap {
        indexedItems.forEach { (index, resolved) ->
            val key = "item_$index"
            val label = resolved.item.name
            val portion = resolved.item.portion
            val cat = resolved.category
            put(
                "${key}_kcal",
                JevQuestion.Score(
                    instructions =
                    "Perkirakan kalori satu porsi $label" +
                        (if (portion.isNotBlank()) " dengan ukuran \"$portion\"" else "") +
                        " dalam kategori ${cat.label}. Gunakan rentang berikut." +
                        (resolved.match?.let {
                            " Acuan katalog lokal: satu ${it.portionLabel} ${it.name} " +
                                "kira-kira ${it.kcalPerPortion} kkal. " +
                                "Pilih rentang yang paling dekat dengan acuan itu, " +
                                "kecuali porsi yang disebutkan jelas lebih besar atau lebih kecil."
                        } ?: "") +
                        (resolved.evidence?.let {
                            " Bukti dari pencarian web: ${it.summary} " +
                                (if (it.kcalReference != null) {
                                    "Sumber menyebut kira-kira ${it.kcalReference} kkal " +
                                        (if (it.portionReference.isNotBlank()) "per ${it.portionReference}. " else ". ")
                                } else {
                                    "Sumber tidak menyebut angka kalori; gunakan pengetahuanmu. "
                                }) +
                                "Pakai bukti ini sebagai patokan, kecuali porsi yang disebutkan " +
                                "pengguna jelas lebih besar atau lebih kecil."
                        } ?: ""),
                    levels = CalorieRubric.criteriaFor(cat),
                ),
            )
            if (calorieOnly) return@forEach
            // The portion multiplier is a Jev decision, not a constant in code: Jev picks
            // how far the portion sits below/above a standard serving, and the scale index
            // is interpolated (0.5x .. 1.8x) rather than snapped to 0.7/1.4.
            put(
                "${key}_portion_scale",
                JevQuestion.Score(
                    instructions =
                    "Dibanding satu porsi standar $label, seberapa besar porsi yang dimakan " +
                        "menurut state ini? Pilih standar bila porsinya standar atau ukurannya " +
                        "tidak disebutkan.",
                    levels = listOf(
                        "jauh lebih kecil (setengah porsi)",
                        "lebih kecil",
                        "standar",
                        "lebih besar",
                        "jauh lebih besar (dua porsi)",
                    ),
                ),
            )
            put(
                "${key}_macro",
                JevQuestion.Choice(
                    instructions = "Kandungan gizi utama (makro) satu porsi $label itu apa?",
                    options = linkedMapOf(
                        "karbohidrat" to "didominasi karbohidrat: nasi, mi, roti, kentang, ubi, bubur",
                        "protein" to "didominasi protein: ayam, ikan, telur, tahu, tempe, daging, susu",
                        "lemak" to "didominasi lemak/minyak: gorengan, santan kental, jeroan berlemak",
                        "serat" to "didominasi serat/vitamin: sayur, buah, lalapan",
                        "seimbang" to "campuran seimbang karbo, protein dan sayur dalam satu hidangan",
                        "tidak_jelas" to "tidak cukup informasi untuk menentukan kandungan utama",
                    ),
                ),
            )
            put(
                "${key}_brand",
                JevQuestion.Noul(
                    instructions =
                    "Apakah \"$label\" menyebut merek, gerai, warung, atau restoran tertentu " +
                        "dan bukan nama makanan generik? Contoh bermerek: Indomie, Ayam " +
                        "Geprek Bensu, Burger Dikichi, KFC. Contoh generik: Nasi Goreng, " +
                        "Sate Ayam, Hamburger.",
                    trueDescription = "ya, ada nama merek/gerai",
                    falseDescription = "tidak, nama makanan generik",
                ),
            )
            put(
                "${key}_local",
                JevQuestion.Noul(
                    instructions = "Apakah \"$label\" adalah makanan atau minuman khas Indonesia?",
                    trueDescription = "ya, khas Indonesia",
                    falseDescription = "bukan khas Indonesia",
                ),
            )
            put(
                "${key}_clear",
                JevQuestion.Noul(
                    instructions =
                    "Apakah \"$label\" cukup jelas untuk dihitung kalorinya tanpa bertanya lagi ke pengguna?",
                    trueDescription = "jelas, bisa dihitung",
                    falseDescription = "ambigu, perlu klarifikasi",
                ),
            )
        }
    }

    // ------------------------------------------------------------- web grounding

    /**
     * Matches an extracted item against the local catalog, rejecting weak matches.
     *
     * `LocalFoodCatalog` matches on substrings, which is right for typo tolerance but
     * too eager for compound brand names: "Burger Ayam Dikichi" would otherwise be
     * claimed by the "ayam" alias of Ayam Goreng. A hit is trusted only when it is an
     * exact name/alias or when it accounts for a meaningful share of the query, so a
     * branded item falls through to Jev and then to web grounding.
     */
    private fun resolveItem(item: ExtractedItem): ResolvedItem {
        val query = item.name.trim()
        val direct = catalogBest(query) ?: catalogSearch(query).firstOrNull()
        val trusted = direct?.takeIf { isStrongMatch(query, it) }
        return ResolvedItem(item, trusted)
    }

    private fun isStrongMatch(query: String, food: id.kaloriku.shared.domain.LocalFood): Boolean {
        val q = query.lowercase()
        val candidates = (listOf(food.name) + food.aliases).map { it.lowercase() }
        if (candidates.any { it == q }) return true
        // The catalog entry that the query itself contains, longest first.
        val contained = candidates.filter { q.contains(it) }.maxByOrNull { it.length } ?: return false
        // Short aliases ("ayam", "nasi") only count when they are most of the phrase.
        return contained.length.toDouble() / q.length >= MIN_MATCH_COVERAGE
    }

    /** Evidence gathered from the web for a food the catalog and Jev could not place. */
    private data class WebEvidence(
        val summary: String,
        val portionReference: String,
        val kcalReference: Int?,
        val source: String,
    )

    /**
     * Finds the items that are genuinely unknown and asks Kenari web search about them.
     *
     * A lookup happens when Jev detects a brand, outlet or restaurant name in the item —
     * that is what determines the calories for packaged and restaurant food — or when the
     * local catalog has no match *and* Jev judges the item neither local nor clearly
     * identifiable. Ordinary Indonesian foods keep their catalog grounding and cost no
     * search.
     *
     * Returns a map from item index to the item enriched with evidence. A failed or
     * irrelevant search simply leaves the index out, so the first Jev estimate stands.
     * Never throws.
     */
    private suspend fun groundUnrecognized(
        items: List<ResolvedItem>,
        jev: JevResponse,
    ): Map<Int, ResolvedItem> {
        val out = mutableMapOf<Int, ResolvedItem>()
        var lookups = 0
        items.forEachIndexed { index, resolved ->
            if (lookups >= maxWebLookups) return@forEachIndexed
            if (!needsWebLookup(index, resolved, jev)) return@forEachIndexed
            lookups++
            val evidence = lookupEvidence(resolved.item.name) ?: return@forEachIndexed
            out[index] = resolved.copy(evidence = evidence)
        }
        return out
    }

    private fun needsWebLookup(index: Int, resolved: ResolvedItem, jev: JevResponse): Boolean {
        // A brand, outlet or restaurant name is the strongest reason to search: the same
        // dish differs a lot between brands, so the generic catalog and Jev's general
        // knowledge are not enough. This outranks the local/clear shortcuts below, so a
        // branded food is grounded even when Jev calls it a familiar Indonesian dish.
        val brand = jev.answers["item_${index}_brand"]?.noul ?: 0.0
        if (brand >= BRAND_THRESHOLD) return true

        if (resolved.match != null) return false
        val local = jev.answers["item_${index}_local"]?.noul ?: 0.5
        val clear = jev.answers["item_${index}_clear"]?.noul ?: 1.0
        // Local food: the catalog just missed a spelling, no search needed.
        if (local >= LOCAL_FOOD_THRESHOLD) return false
        // Clear, non-local food Jev already knows (e.g. "Hamburger"): nothing to ground.
        if (clear >= CLEAR_ENOUGH_THRESHOLD) return false
        return true
    }

    /** Searches the web and reduces the hits to a compact fact block. Never throws. */
    private suspend fun lookupEvidence(name: String): WebEvidence? {
        val query = "kalori $name per porsi kandungan gizi"
        val results = runCatching { api.webSearch(query, maxResults = 3) }.getOrElse { return null }
        val usable = results.filter { it.snippet.isNotBlank() }
        if (usable.isEmpty()) return null
        val block = usable.take(3).joinToString("\n") { hit ->
            buildString {
                append("- ")
                if (hit.title.isNotBlank()) append(hit.title)
                if (hit.url.isNotBlank()) append(" (${hit.url})")
                append(": ")
                append(hit.snippet.take(700))
            }
        }
        val json = runCatching {
            parseJsonObject(
                api.chat(
                    Prompts.WEB_LOOKUP,
                    "Makanan yang dicari: \"$name\"\n\nHasil pencarian:\n$block",
                    jsonMode = true,
                ),
            )
        }.getOrNull() ?: return null
        if (!json.optBoolean("dikenali", false)) return null
        val summary = json.optString("ringkasan").trim()
        if (summary.isEmpty()) return null
        val kcal = if (json.has("kcal_acuan") && !json.isNull("kcal_acuan")) {
            json.optDouble("kcal_acuan").takeIf { it.isFinite() && it > 0 }?.roundToInt()
        } else {
            null
        }
        return WebEvidence(
            summary = summary.take(400),
            portionReference = json.optString("porsi_acuan").trim().take(80),
            kcalReference = kcal,
            source = json.optString("sumber").trim().take(120),
        )
    }

    private fun buildState(
        transcript: String,
        extraction: Extraction,
        items: List<ResolvedItem>,
        nowMillis: Long,
    ): String {
        val hour = JakartaTime.hourOfDay(nowMillis)
        val dayKey = JakartaTime.dayKey(nowMillis)
        val itemLines = items.joinToString("\n") { r ->
            val cat = r.match?.let {
                "${it.category.label} | acuan katalog: ${it.kcalPerPortion} kkal per ${it.portionLabel}"
            } ?: "tidak ada di katalog lokal"
            val web = r.evidence?.let { e ->
                "\n    Bukti web (${e.source.ifBlank { "pencarian" }}): ${e.summary}" +
                    (if (e.kcalReference != null) {
                        " | acuan sumber: ${e.kcalReference} kkal" +
                            (if (e.portionReference.isNotBlank()) " per ${e.portionReference}" else "")
                    } else {
                        " | sumber tidak menyebut angka kalori"
                    })
            } ?: ""
            "- ${r.item.name}" +
                (if (r.item.portion.isNotBlank()) " | porsi disebut: ${r.item.portion}" else "") +
                (if (r.item.grams != null) " | ${r.item.grams} gram" else "") +
                " | kandidat kategori: $cat" +
                web
        }
        return buildString {
            appendLine("Transkrip suara bahasa Indonesia: \"$transcript\"")
            appendLine("Ringkasan: ${extraction.normalized}")
            appendLine("Waktu sekarang: $dayKey pukul ${"%02d".format(hour)}:00 WIB.")
            appendLine("Daftar makanan yang terdeteksi:")
            append(itemLines)
            appendLine()
            appendLine(
                "Catatan: nilai acuan katalog adalah estimasi porsi standar yang sudah " +
                    "diverifikasi untuk makanan Indonesia. Gunakan sebagai patokan utama, " +
                    "dan sesuaikan hanya jika porsi yang disebutkan berbeda. Bukti web " +
                    "dipakai untuk makanan bermerek atau dari restoran yang tidak ada di katalog.",
            )
        }
    }

    private fun fallbackJev(
        items: List<ResolvedItem>,
        mealHint: MealType?,
        nowMillis: Long,
    ): JevResponse {
        val answers = buildMap<String, JevAnswer> {
            put("health", JevAnswer(type = "score", score = 3.0))
            put(
                "meal",
                JevAnswer(
                    type = "choice",
                    choice = (mealHint ?: mealFromHour(JakartaTime.hourOfDay(nowMillis))).name,
                    confidence = 0.5,
                ),
            )
            items.forEachIndexed { index, resolved ->
                val cat = resolved.category
                val ranges = CalorieRubric.buckets.getValue(cat)
                val base = resolved.match?.kcalPerPortion
                    ?: ranges[ranges.size / 2].first
                val idx = ranges.indexOfFirst { base in it }.coerceAtLeast(0)
                put(
                    "item_${index}_kcal",
                    JevAnswer(
                        type = "score",
                        score = idx.toDouble(),
                        confidence = if (resolved.match != null) 0.8 else 0.4,
                        probabilities = mapOf(idx.toString() to 1.0),
                        legend = CalorieRubric.criteriaFor(cat).mapIndexed { i, s -> i.toString() to s }.toMap(),
                    ),
                )
                // Without Jev there is no portion judgement: standard serving (index 2).
                put(
                    "item_${index}_portion_scale",
                    JevAnswer(
                        type = "score",
                        score = 2.0,
                        confidence = 0.4,
                        probabilities = mapOf("2" to 1.0),
                        legend = PORTION_SCALE_LEGEND,
                    ),
                )
                put(
                    "item_${index}_macro",
                    JevAnswer(type = "choice", choice = "tidak_jelas", confidence = 0.4),
                )
                put("item_${index}_local", JevAnswer(type = "noul", noul = if (resolved.match != null) 1.0 else 0.3))
                put("item_${index}_clear", JevAnswer(type = "noul", noul = if (resolved.match != null) 1.0 else 0.6))
                // Without Jev there is no brand judgement, so no search is spent.
                put("item_${index}_brand", JevAnswer(type = "noul", noul = 0.0))
            }
        }
        return JevResponse(model = "fallback-lokal", answers = answers)
    }

    // ------------------------------------------------------------------- mapping

    private fun toAnalyzedItem(index: Int, resolved: ResolvedItem, jev: JevResponse): AnalyzedItem {
        val kcalAnswer = jev.answers["item_${index}_kcal"]
        val portionScale = jev.answers["item_${index}_portion_scale"]
        val macroAnswer = jev.answers["item_${index}_macro"]
        val localAnswer = jev.answers["item_${index}_local"]
        val clearAnswer = jev.answers["item_${index}_clear"]

        // Jev's portion scale, interpolated over the multiplier table below. When Jev did
        // not answer (degraded), the estimate stays at the standard portion.
        val multiplier = portionScale?.let { scale ->
            val idx = (scale.weightedIndex() ?: PORTION_SCALE_STANDARD).coerceIn(
                0.0,
                (PORTION_SCALE.size - 1).toDouble(),
            )
            val lo = idx.toInt().coerceIn(0, PORTION_SCALE.size - 1)
            val hi = (lo + 1).coerceAtMost(PORTION_SCALE.size - 1)
            val frac = idx - lo
            PORTION_SCALE[lo] + frac * (PORTION_SCALE[hi] - PORTION_SCALE[lo])
        } ?: 1.0
        val (point, low, high) = if (kcalAnswer != null) {
            CalorieRubric.interpret(kcalAnswer, resolved.category)
        } else {
            val base = resolved.match?.kcalPerPortion ?: 0
            Triple(base, base, base)
        }
        val scaledPoint = (point * multiplier).roundToInt()
        val scaledLow = (low * multiplier).roundToInt()
        val scaledHigh = (high * multiplier).roundToInt()

        return AnalyzedItem(
            name = resolved.item.name,
            canonicalName = resolved.match?.id,
            portionText = resolved.item.portion.ifBlank { resolved.match?.portionLabel ?: "" },
            grams = resolved.item.grams,
            kcal = scaledPoint,
            kcalLow = scaledLow,
            kcalHigh = scaledHigh,
            confidence = kcalAnswer?.confidence ?: 0.5,
            isLocal = localAnswer?.noulYes ?: (resolved.match != null),
            needsClarification = clearAnswer?.noul?.let { it < 0.35 } ?: false,
            webGrounded = resolved.evidence != null,
            macroProfile = macroAnswer?.choice?.takeIf { it in MACRO_PROFILES } ?: "tidak_jelas",
        )
    }

    private fun resolveMeal(jev: JevResponse, hint: MealType?, nowMillis: Long): MealType {
        val choice = jev.answers["meal"]?.choice
        return if (choice != null && jev.answers["meal"]?.confidence?.let { it >= 0.4 } != false) {
            MealType.fromKey(choice)
        } else {
            hint ?: mealFromHour(JakartaTime.hourOfDay(nowMillis))
        }
    }

    private fun detectDuplicate(jev: JevResponse, existing: List<FoodEntry>): Long? {
        if (existing.isEmpty()) return null
        val answer = jev.answers["duplicate"] ?: return null
        // The duplicate question is a choice naming the matching candidate (or "BARU").
        val choice = answer.choice ?: return null
        if (!choice.startsWith(DUP_PREFIX)) return null
        val index = choice.removePrefix(DUP_PREFIX).toIntOrNull() ?: return null
        return existing.take(8).getOrNull(index)?.id
    }

    private fun emptyResult(
        transcript: String,
        normalized: String = transcript,
        nowMillis: Long,
    ) = AnalysisResult(
        transcript = transcript,
        normalized = normalized,
        items = emptyList(),
        meal = mealFromHour(JakartaTime.hourOfDay(nowMillis)),
        healthScore = 0.0,
        needsClarification = true,
        totalKcal = 0,
        totalLow = 0,
        totalHigh = 0,
        engine = "",
    )
    companion object {
        /** Valid Jev macro-profile answers. Anything else falls back to tidak_jelas. */
        private val MACRO_PROFILES = setOf(
            "karbohidrat", "protein", "lemak", "serat", "seimbang", "tidak_jelas",
        )
        /**
         * Jev P(yes) at or above which an item is treated as carrying a brand, outlet or
         * restaurant name. Branded items are always grounded with a web search, because
         * the brand is what determines the calories.
         */
        private const val BRAND_THRESHOLD = 0.5

        /**
         * Jev P(yes) at or above which an item is treated as local Indonesian food.
         * A catalog miss above this is just a spelling gap, so no web lookup is made.
         */
        private const val LOCAL_FOOD_THRESHOLD = 0.5

        /**
         * Jev P(yes) at or above which an item is considered clear enough to estimate
         * without help. Below both this and [LOCAL_FOOD_THRESHOLD] the item is treated
         * as a branded/unknown food and grounded with a web search.
         */
        private const val CLEAR_ENOUGH_THRESHOLD = 0.6

        /**
         * How much of a food phrase a catalog name or alias must cover to be trusted.
         * Below this, the phrase is treated as a compound/brand name (for example
         * "Burger Ayam Dikichi" against the "ayam" alias) and left for Jev and the web.
         */
        private const val MIN_MATCH_COVERAGE = 0.5

        /**
         * Portion multipliers for Jev's `_portion_scale` score, indexed by the score
         * level. Index 2 is the standard serving, so a mid-level answer reproduces the
         * un-scaled estimate; the values are interpolated for fractional answers.
         */
        private val PORTION_SCALE = listOf(0.5, 0.75, 1.0, 1.35, 1.8)
        private const val PORTION_SCALE_STANDARD = 2.0
        private val PORTION_SCALE_LEGEND = mapOf(
            "0" to "jauh lebih kecil (setengah porsi)",
            "1" to "lebih kecil",
            "2" to "standar",
            "3" to "lebih besar",
            "4" to "jauh lebih besar (dua porsi)",
        )

        /** Marks the engine string of a result produced without a live Jev call. */
        const val DEGRADED_ENGINE = "degraded-tanpa-jev"

        /** Choice-answer prefix that names the recent entry a new log duplicates. */
        private const val DUP_PREFIX = "CATATAN_"

        fun mealFromKey(key: String): MealType = when (key.lowercase().replace(" ", "_")) {
            "sarapan", "pagi", "breakfast" -> MealType.SARAPAN
            "makan_siang", "siang", "lunch" -> MealType.MAKAN_SIANG
            "makan_malam", "malam", "dinner" -> MealType.MAKAN_MALAM
            else -> MealType.CAMILAN
        }

        fun mealFromHour(hour: Int): MealType = when (hour) {
            in 4..10 -> MealType.SARAPAN
            in 11..14 -> MealType.MAKAN_SIANG
            in 17..21 -> MealType.MAKAN_MALAM
            else -> MealType.CAMILAN
        }

        internal fun parseJsonObject(raw: String): JSONObject {
            val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            val candidate = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
            return runCatching { JSONObject(candidate) }.getOrElse { throw KenariException("JSON tidak valid dari model.") }
        }
    }
}
