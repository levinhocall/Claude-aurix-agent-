package com.aurix.agent.core.net

import com.aurix.agent.core.tools.web.isPrivateAddress
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/** True for localhost, *.local and private/loopback IP literals (LAN, Tailscale 100.64/10 etc.). Hostnames are never DNS-resolved here. */
fun isLocalNetworkHost(host: String): Boolean {
    val h = host.lowercase().trim('[', ']')
    if (h == "localhost" || h.endsWith(".local")) return true
    if (!(h.contains(':') || Regex("^[0-9.]+$").matches(h))) return false
    return try { isPrivateAddress(InetAddress.getByName(h)) } catch (e: Exception) { false }
}

/** The app allows cleartext at the platform level (needed for LAN model servers) but this guard restricts plain http to the local network. */
object CleartextGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.scheme == "http" && !isLocalNetworkHost(req.url.host))
            throw IOException("Plain http is only allowed for local-network hosts; use https")
        return chain.proceed(req)
    }
}
