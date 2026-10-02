package com.aurix.agent.core.tools.web

import com.aurix.agent.core.net.awaitResponse
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal fun isPrivateAddress(a: InetAddress): Boolean =
    a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress ||
        (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC) ||
        (a is Inet4Address && a.address[0].toInt() == 100 && (a.address[1].toInt() and 0xC0) == 0x40)

data class FetchedPage(val url: String, val contentType: String, val body: String)

/**
 * HTTP GET for the agent with SSRF protection: only http(s), no private/loopback/link-local targets
 * (checked at DNS time and for IP literals), manual redirect handling with the same checks on every hop,
 * size and time limits.
 */
@Singleton
class SafeFetcher @Inject constructor(client: OkHttpClient) {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = Dns.SYSTEM.lookup(hostname)
                if (all.any { isPrivateAddress(it) }) throw UnknownHostException("Blocked: private address")
                return all
            }
        })
        .build()

    suspend fun get(url: String, maxBytes: Long = 1_500_000L): FetchedPage = withContext(Dispatchers.IO) {
        var current = url
        for (hop in 0..5) {
            val u = current.toHttpUrlOrNull() ?: throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid URL: $current")
            if (u.scheme != "http" && u.scheme != "https") throw ToolException(ToolErrorType.INVALID_INPUT, "Only http/https URLs are allowed")
            val host = u.host
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                val lit = try { InetAddress.getByName(host) } catch (e: Exception) { throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid host") }
                if (isPrivateAddress(lit)) throw ToolException(ToolErrorType.INVALID_INPUT, "Blocked: private address")
            }
            val req = Request.Builder().url(u)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "en-US,en;q=0.8")
                .build()
            val resp = try {
                http.newCall(req).awaitResponse()
            } catch (e: SocketTimeoutException) {
                throw ToolException(ToolErrorType.TIMEOUT, "Request timed out")
            } catch (e: UnknownHostException) {
                if (e.message?.startsWith("Blocked") == true) throw ToolException(ToolErrorType.INVALID_INPUT, "Blocked: private address")
                throw ToolException(ToolErrorType.NETWORK_ERROR, "DNS lookup failed for $host")
            } catch (e: IOException) {
                throw ToolException(ToolErrorType.NETWORK_ERROR, "Network error (${e.javaClass.simpleName})")
            }
            val next: String = resp.use { r ->
                if (r.code in 300..399) {
                    val loc = r.header("Location") ?: throw ToolException(ToolErrorType.TOOL_ERROR, "Redirect without Location")
                    u.resolve(loc)?.toString() ?: throw ToolException(ToolErrorType.TOOL_ERROR, "Bad redirect target")
                } else {
                    when {
                        r.code == 429 -> throw ToolException(ToolErrorType.RATE_LIMIT, "HTTP 429 (rate limited by site)")
                        r.code in 500..599 -> throw ToolException(ToolErrorType.NETWORK_ERROR, "HTTP ${r.code} from site")
                        r.code !in 200..299 -> throw ToolException(ToolErrorType.TOOL_ERROR, "HTTP ${r.code}")
                    }
                    val ct = r.header("Content-Type").orEmpty().lowercase()
                    if (!(ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("html")))
                        throw ToolException(ToolErrorType.TOOL_ERROR, "Unsupported content type: ${ct.ifEmpty { "unknown" }}")
                    val body = r.peekBody(maxBytes).string()
                    return@withContext FetchedPage(u.toString(), ct, body)
                }
            }
            current = next
        }
        throw ToolException(ToolErrorType.TOOL_ERROR, "Too many redirects")
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
