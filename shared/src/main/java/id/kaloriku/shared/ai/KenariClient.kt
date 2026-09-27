package id.kaloriku.shared.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import id.kaloriku.shared.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kenari gateway client. Two wires are used:
 *  - `POST {base}/chat/completions` with [CHAT_MODEL] for language tasks.
 *  - `POST {base}/systemone` with [JEV_MODEL] for typed decisions.
 *
 * The API key and the model ids are build constants, not user settings: the key is
 * injected from `KENARI_API_KEY` at build time and the app works out of the box.
 */
class KenariClient(
    private val apiKeyProvider: () -> String = { BuildConfig.KENARI_API_KEY_DEFAULT },
    baseUrl: String = DEFAULT_BASE_URL,
) : KenariApi {

    private val base = baseUrl.trimEnd('/')

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun chat(system: String, user: String, jsonMode: Boolean): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            }
            val body = JSONObject().apply {
                put("model", CHAT_MODEL)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 1200)
                if (jsonMode) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }
            val json = post("/chat/completions", body)
            val choices = json.optJSONArray("choices")
                ?: throw KenariException("Respons chat tidak punya 'choices'.")
            if (choices.length() == 0) throw KenariException("Respons chat kosong.")
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw KenariException("Respons chat tidak punya konten.")
        }

    override suspend fun jev(state: String, questions: Map<String, JevQuestion>): JevResponse =
        withContext(Dispatchers.IO) {
            require(questions.isNotEmpty()) { "Minimal satu pertanyaan Jev." }
            val qJson = JSONObject().apply {
                questions.forEach { (name, q) -> put(name, q.toJson()) }
            }
            val body = JSONObject().apply {
                put("model", JEV_MODEL)
                put("state", state)
                put("questions", qJson)
            }
            val json = post("/systemone", body)
            val answersJson = json.optJSONObject("answers")
                ?: throw KenariException("Respons Jev tidak punya 'answers'.")
            val answers = buildMap {
                val keys = answersJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val a = answersJson.optJSONObject(key) ?: continue
                    put(key, parseAnswer(a))
                }
            }
            val usage = json.optJSONObject("usage")
            JevResponse(
                model = json.optString("model", JEV_MODEL),
                answers = answers,
                inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
                outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
            )
        }

    /**
     * `POST /v1/web/search`. Used to ground branded or restaurant foods that the
     * built-in catalog does not know. Never throws: a failed lookup yields an empty
     * list so the analysis pipeline can fall back to Jev's own knowledge.
     */
    override suspend fun webSearch(query: String, maxResults: Int): List<WebSearchResult> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val body = JSONObject().apply {
                put("query", q)
                put("max_results", maxResults.coerceIn(1, 8))
            }
            runCatching {
                val json = post("/web/search", body)
                val arr = json.optJSONArray("results") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title").trim()
                        val url = o.optString("url").trim()
                        val snippet = o.optString("content").trim()
                        if (title.isEmpty() && snippet.isEmpty()) continue
                        add(WebSearchResult(title = title, url = url, snippet = snippet))
                    }
                }
            }.getOrElse { emptyList() }
        }

    private fun parseAnswer(a: JSONObject): JevAnswer {
        val probabilities = a.optJSONObject("probabilities")?.let { probs ->
            buildMap {
                val keys = probs.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, probs.optDouble(k, 0.0))
                }
            }
        } ?: emptyMap()
        val legend = a.optJSONObject("legend")?.let { l ->
            buildMap {
                val keys = l.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, l.optString(k))
                }
            }
        } ?: emptyMap()
        return JevAnswer(
            type = a.optString("type"),
            noul = if (a.has("noul")) a.optDouble("noul") else null,
            choice = if (a.has("choice") && !a.isNull("choice")) a.optString("choice") else null,
            confidence = if (a.has("confidence")) a.optDouble("confidence") else null,
            score = if (a.has("score")) a.optDouble("score") else null,
            probabilities = probabilities,
            legend = legend,
        )
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            throw KenariException(
                "Kunci Kenari tidak tertanam di build ini. " +
                    "Build ulang dengan variabel lingkungan KENARI_API_KEY terisi.",
            )
        }
        var lastError: String = "Tidak diketahui"
        repeat(3) { attempt ->
            val request = Request.Builder()
                .url("$base$path")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            val outcome = try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (response.code in 200..299) {
                        PostOutcome.Success(text)
                    } else {
                        val apiError = runCatching {
                            JSONObject(text).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        lastError = apiError ?: "HTTP ${response.code}"
                        PostOutcome.Failure(response.code, lastError)
                    }
                }
            } catch (e: IOException) {
                lastError = e.message ?: "koneksi gagal"
                PostOutcome.Network
            }

            when (outcome) {
                is PostOutcome.Success -> return JSONObject(outcome.text)
                is PostOutcome.Failure -> {
                    when (outcome.code) {
                        401 -> throw KenariException("Kunci Kenari ditolak (401). Periksa kembali kunci Anda.")
                        402, 403 -> throw KenariException("Saldo/kuota Kenari tidak cukup: ${outcome.message}")
                        400 -> throw KenariException("Permintaan ditolak Kenari (400): ${outcome.message}")
                        // delay() rather than Thread.sleep so a cancelled analysis releases
                        // the thread immediately instead of finishing its backoff first.
                        429 -> if (attempt < 2) delay(1_500L * (attempt + 1)) else {
                            throw KenariException("Kenari sedang sibuk (429). Coba lagi sebentar.")
                        }
                        else -> if (attempt == 2) {
                            throw KenariException("Kenari gagal (${outcome.code}): ${outcome.message}")
                        }
                    }
                }
                PostOutcome.Network -> {
                    if (attempt == 2) {
                        throw KenariException("Tidak bisa menghubungi Kenari: $lastError")
                    }
                    delay(800L * (attempt + 1))
                }
            }
        }
        throw KenariException("Kenari gagal setelah beberapa percobaan: $lastError")
    }

    private sealed interface PostOutcome {
        data class Success(val text: String) : PostOutcome
        data class Failure(val code: Int, val message: String) : PostOutcome
        data object Network : PostOutcome
    }

    companion object {
        /** Kenari gateway root; endpoints are appended to it. */
        const val DEFAULT_BASE_URL = "https://kenari.id/v1"

        /** Language model for normalization, entity extraction and Indonesian prose. */
        const val CHAT_MODEL = "deepseek-v4-1-flash"

        /** System One (Jev) model used for every typed decision. */
        const val JEV_MODEL = "jev-1-13-free"

        /** True when a key was baked into this build. */
        val isKeyConfigured: Boolean
            get() = BuildConfig.KENARI_API_KEY_DEFAULT.isNotBlank()
    }
}
