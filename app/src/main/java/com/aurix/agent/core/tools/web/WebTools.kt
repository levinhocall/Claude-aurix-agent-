package com.aurix.agent.core.tools.web

import com.aurix.agent.core.tools.RetryPolicy
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject
import org.jsoup.Jsoup

class WebSearchTool(private val search: SearchManager) : Tool {
    override val name = "WEB_SEARCH"
    override val description = "Search the web. Returns titles, URLs and snippets. Use short, specific queries; try different wording if results are poor."
    override val inputSchema = """{"query":"search terms","max_results":8}"""
    override val outputSchema = "numbered list of results"
    override val required = listOf("query")
    override val permissions = listOf("network")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 75_000L
    override val retry = RetryPolicy(maxAttempts = 2, backoffMs = 1_500)

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val max = input.optInt("max_results", 8).coerceIn(1, 15)
        val (provider, results) = search.search(input.optString("query").trim(), max)
        val text = results.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet.take(250)}" }.joinToString("\n")
        return ToolResult.ok("[via $provider]\n$text")
    }
}

class WebBrowserTool(private val fetcher: SafeFetcher) : Tool {
    override val name = "WEB_BROWSER"
    override val description = "Open a web page and read its main text plus top links. Works for HTML, plain text and JSON pages. Does not run JavaScript."
    override val inputSchema = """{"url":"https://...","max_chars":6000}"""
    override val outputSchema = "title, text, links"
    override val required = listOf("url")
    override val permissions = listOf("network")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 45_000L
    override val retry = RetryPolicy(maxAttempts = 2, backoffMs = 1_500)

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val max = input.optInt("max_chars", 6000).coerceIn(500, 12_000)
        val page = fetcher.get(input.optString("url").trim())
        if (!page.contentType.contains("html")) {
            val t = page.body
            return ToolResult.ok("URL: ${page.url}\n" + if (t.length > max) t.take(max) + "\n[truncated]" else t)
        }
        val doc = Jsoup.parse(page.body, page.url)
        val title = doc.title()
        doc.select("script,style,noscript,nav,footer,header,aside,form,svg").remove()
        val root = doc.selectFirst("article, main") ?: doc.body()
        val lines = root.select("h1,h2,h3,p,li,td,th,pre").map { it.text().trim() }.filter { it.isNotEmpty() }
        val deduped = lines.filterIndexed { i, l -> i == 0 || l != lines[i - 1] }
        val text = deduped.joinToString("\n").ifBlank { root.text() }
        val links = root.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            val label = a.text().trim()
            if (href.startsWith("http") && label.isNotEmpty()) "$label — $href" else null
        }.distinct().take(12)
        val body = if (text.length > max) text.take(max) + "\n[truncated, ${text.length - max} more chars]" else text
        return ToolResult.ok("URL: ${page.url}\nTITLE: $title\nTEXT:\n$body" + if (links.isNotEmpty()) "\nLINKS:\n" + links.joinToString("\n") else "")
    }
}
