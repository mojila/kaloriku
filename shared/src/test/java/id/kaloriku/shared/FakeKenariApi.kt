package id.kaloriku.shared

import id.kaloriku.shared.ai.JevAnswer
import id.kaloriku.shared.ai.JevQuestion
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.KenariApi
import id.kaloriku.shared.ai.KenariException
import id.kaloriku.shared.ai.WebSearchResult

/**
 * A scripted [KenariApi] for unit tests. Chat returns canned JSON; Jev returns
 * prebuilt answers; web search returns scripted hits, so tests never touch the network.
 */
class FakeKenariApi(
    private val chatResponses: MutableList<String> = mutableListOf(),
    private val jevResponses: MutableList<JevResponse> = mutableListOf(),
    private val searchResponses: MutableList<List<WebSearchResult>> = mutableListOf(),
) : KenariApi {

    var lastChatUser: String? = null
    var lastJevState: String? = null
    var lastJevQuestions: Map<String, JevQuestion>? = null
    var lastSearchQuery: String? = null
    var chatCalls = 0
    var jevCalls = 0
    var searchCalls = 0

    var chatThrows: KenariException? = null
    var jevThrows: KenariException? = null

    fun enqueueChat(json: String) = apply { chatResponses += json }
    fun enqueueJev(response: JevResponse) = apply { jevResponses += response }
    fun enqueueSearch(results: List<WebSearchResult>) = apply { searchResponses += results }

    override suspend fun chat(system: String, user: String, jsonMode: Boolean): String {
        chatCalls++
        lastChatUser = user
        chatThrows?.let { throw it }
        if (chatResponses.isEmpty()) throw KenariException("no scripted chat response")
        return chatResponses.removeAt(0)
    }

    override suspend fun jev(state: String, questions: Map<String, JevQuestion>): JevResponse {
        jevCalls++
        lastJevState = state
        lastJevQuestions = questions
        jevThrows?.let { throw it }
        if (jevResponses.isEmpty()) throw KenariException("no scripted jev response")
        return jevResponses.removeAt(0)
    }

    override suspend fun webSearch(query: String, maxResults: Int): List<WebSearchResult> {
        searchCalls++
        lastSearchQuery = query
        if (searchResponses.isEmpty()) return emptyList()
        return searchResponses.removeAt(0)
    }

    companion object {
        fun scoreAnswer(score: Double, probs: Map<String, Double> = emptyMap()): JevAnswer =
            JevAnswer(
                type = "score",
                score = score,
                confidence = 0.8,
                probabilities = probs,
                legend = emptyMap(),
            )

        fun noulAnswer(p: Double): JevAnswer = JevAnswer(type = "noul", noul = p)

        fun choiceAnswer(choice: String, confidence: Double = 0.9): JevAnswer =
            JevAnswer(type = "choice", choice = choice, confidence = confidence)
    }
}
