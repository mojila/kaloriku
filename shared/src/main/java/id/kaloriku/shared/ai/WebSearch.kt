package id.kaloriku.shared.ai

/**
 * One hit from Kenari's web search (`POST /v1/web/search`).
 *
 * Search is only a source of facts: Jev flags the items that name a brand, outlet or
 * restaurant (for example "Burger Ayam Dikichi", "Indomie Goreng"), because the brand
 * is what determines their calories, and those items are looked up. Foods the local
 * catalog does not know are grounded the same way. The calorie/portion decisions are
 * still made by Jev, with these snippets as evidence.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * The web-lookup surface the analysis pipeline depends on, so tests can fake it
 * without touching the network.
 */
interface WebSearchApi {
    /** Searches the web and returns up to [maxResults] hits, newest relevance first. */
    suspend fun webSearch(query: String, maxResults: Int = 3): List<WebSearchResult>
}
