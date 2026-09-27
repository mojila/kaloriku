package id.kaloriku.shared.ai

import org.json.JSONObject

/**
 * Typed System One (Jev) questions.
 *
 * Jev is not a chat model: it judges a [state] and answers named questions with
 * calibrated probabilities. We use it for every decision in the calorie pipeline.
 */
sealed interface JevQuestion {
    val instructions: String

    /** A yes/no judgment; the answer carries `P(yes)` in [0, 1]. */
    data class Noul(
        override val instructions: String,
        val trueDescription: String? = null,
        val falseDescription: String? = null,
    ) : JevQuestion

    /** Pick exactly one of 2..255 named options. */
    data class Choice(
        override val instructions: String,
        val options: Map<String, String>,
    ) : JevQuestion {
        init {
            require(options.size in 2..255) {
                "A Jev choice needs 2..255 options, got ${options.size}."
            }
        }
    }

    /** An ordered low-to-high scale of 2..10 levels. */
    data class Score(
        override val instructions: String,
        val levels: List<String>,
    ) : JevQuestion {
        init {
            require(levels.size in 2..10) {
                "A Jev score needs 2..10 levels, got ${levels.size}."
            }
        }
    }
}

/** One typed answer from Jev. Fields are populated according to [type]. */
data class JevAnswer(
    val type: String,
    val noul: Double? = null,
    val choice: String? = null,
    val confidence: Double? = null,
    val score: Double? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val legend: Map<String, String> = emptyMap(),
) {
    val noulYes: Boolean get() = (noul ?: 0.0) >= 0.5

    /** Point estimate mapped onto [0, size-1] using the score distribution. */
    fun weightedIndex(): Double? {
        val probs = probabilities
        if (probs.isEmpty()) return score
        var sum = 0.0
        var weight = 0.0
        for ((key, p) in probs) {
            val idx = key.toIntOrNull() ?: continue
            sum += idx * p
            weight += p
        }
        return if (weight <= 0.0) score else sum / weight
    }
}

/** The full System One response. */
data class JevResponse(
    val model: String,
    val answers: Map<String, JevAnswer>,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    /**
     * Overlays [later] on top of this response, per question name.
     *
     * Used when a second Jev call re-asks only the calorie questions of the items
     * that got web evidence: the global answers (meal, health, duplicate) and the
     * untouched items keep the first pass, and the re-asked items take the new one.
     */
    fun merge(later: JevResponse): JevResponse = JevResponse(
        model = later.model.ifBlank { model },
        answers = answers + later.answers,
        inputTokens = inputTokens + later.inputTokens,
        outputTokens = outputTokens + later.outputTokens,
    )
}

/** Thrown when the Kenari API cannot be reached or returns an error. */
class KenariException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The narrow surface the analysis pipeline depends on, so tests can fake it. */
interface KenariApi : WebSearchApi {
    /** Chat completion returning the assistant text. [jsonMode] sets response_format. */
    suspend fun chat(system: String, user: String, jsonMode: Boolean = false): String

    /** System One (Jev) typed decision call. */
    suspend fun jev(state: String, questions: Map<String, JevQuestion>): JevResponse

    /**
     * Kenari web search, used to ground branded/restaurant items and foods the local
     * catalog cannot identify. Jev decides *whether* a lookup is needed — its brand
     * question is the primary trigger; this only fetches the evidence.
     */
    override suspend fun webSearch(query: String, maxResults: Int): List<WebSearchResult>
}

/** Serialises a [JevQuestion] into the wire shape expected by `/v1/systemone`. */
internal fun JevQuestion.toJson(): JSONObject = JSONObject().apply {
    when (this@toJson) {
        is JevQuestion.Noul -> {
            put("type", "noul")
            put("instructions", instructions)
            if (trueDescription != null || falseDescription != null) {
                put(
                    "criteria",
                    JSONObject().apply {
                        put("true", trueDescription ?: "yes")
                        put("false", falseDescription ?: "no")
                    },
                )
            }
        }
        is JevQuestion.Choice -> {
            put("type", "choice")
            put("instructions", instructions)
            put("criteria", JSONObject().apply { options.forEach { (k, v) -> put(k, v) } })
        }
        is JevQuestion.Score -> {
            put("type", "score")
            put("instructions", instructions)
            put("criteria", org.json.JSONArray().apply { levels.forEach { put(it) } })
        }
    }
}
