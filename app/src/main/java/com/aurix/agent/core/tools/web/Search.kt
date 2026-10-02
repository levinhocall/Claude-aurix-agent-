package com.aurix.agent.core.tools.web

import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResult(val title: String, val url: String, val snippet: String)

/** Plug-in point: add Brave/Tavily/SearXNG/etc. by implementing this. */
interface SearchProvider {
    val id: String
    suspend fun search(query: String, max: Int): List<SearchResult>
}

internal fun decodeDdgUrl(href: String): String? {
    val h = if (href.startsWith("//")) "https:$href" else href
    val u = h.toHttpUrlOrNull() ?: return null
    val target = u.queryParameter("uddg")
    if (target != null) return target
    return if (u.host.endsWith("duckduckgo.com")) null else h
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

class DuckDuckGoHtmlProvider(private val fetcher: SafeFetcher) : SearchProvider {
    override val id = "duckduckgo-html"
    override suspend fun search(query: String, max: Int): List<SearchResult> {
        val page = fetcher.get("https://html.duckduckgo.com/html/?q=${enc(query)}")
        val doc = Jsoup.parse(page.body, page.url)
        return doc.select("div.result").mapNotNull { el ->
            if (el.hasClass("result--ad")) return@mapNotNull null
            val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = decodeDdgUrl(a.attr("href")) ?: return@mapNotNull null
            SearchResult(a.text(), url, el.selectFirst(".result__snippet")?.text().orEmpty())
        }.take(max)
    }
}

class DuckDuckGoLiteProvider(private val fetcher: SafeFetcher) : SearchProvider {
    override val id = "duckduckgo-lite"
    override suspend fun search(query: String, max: Int): List<SearchResult> {
        val page = fetcher.get("https://lite.duckduckgo.com/lite/?q=${enc(query)}")
        val doc = Jsoup.parse(page.body, page.url)
        val links = doc.select("a.result-link")
        val snippets = doc.select("td.result-snippet")
        return links.mapIndexedNotNull { i, a ->
            val url = decodeDdgUrl(a.attr("href")) ?: return@mapIndexedNotNull null
            SearchResult(a.text(), url, snippets.getOrNull(i)?.text().orEmpty())
        }.take(max)
    }
}

class WikipediaProvider(private val fetcher: SafeFetcher) : SearchProvider {
    override val id = "wikipedia"
    override suspend fun search(query: String, max: Int): List<SearchResult> {
        val page = fetcher.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&utf8=1&format=json&srlimit=$max")
        val arr = JSONObject(page.body).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.optString("title")
            SearchResult(title, "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_')), Jsoup.parse(o.optString("snippet")).text())
        }
    }
}

/** Tries providers in order; first one that returns results wins. */
@Singleton
class SearchManager @Inject constructor(fetcher: SafeFetcher) {
    private val providers: List<SearchProvider> = listOf(DuckDuckGoHtmlProvider(fetcher), DuckDuckGoLiteProvider(fetcher), WikipediaProvider(fetcher))

    suspend fun search(query: String, max: Int): Pair<String, List<SearchResult>> {
        val errors = mutableListOf<String>()
        for (p in providers) {
            try {
                val r = p.search(query, max)
                if (r.isNotEmpty()) return p.id to r
                errors += "${p.id}: no results"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${p.id}: ${e.message?.take(80)}"
            }
        }
        throw ToolException(ToolErrorType.TOOL_ERROR, "All search providers failed (${errors.joinToString("; ")}). Try a different query.")
    }
}
