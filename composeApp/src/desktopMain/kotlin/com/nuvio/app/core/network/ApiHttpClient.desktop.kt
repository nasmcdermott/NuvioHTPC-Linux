package com.nuvio.app.core.network

import com.nuvio.app.features.vpn.VpnBindHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin

/** The HTTP client the media-server providers use (upstream's `createApiHttpClient`). */
internal actual fun createApiHttpClient(): HttpClient {
    // VPN bind (fork:linux): when a mode is on, everything rides the fail-closed relay; its
    // refusal of an unbindable upstream is BLOCK made real. Loopback media servers stay direct
    // (VpnBindProxySelector-style policy is enforced by the relay's targets only for OkHttp; here
    // the providers point at remote servers).
    val bindProxy = VpnBindHttp.relayProxy()
    val bindAuthorization = VpnBindHttp.relayProxyAuthorization()
    return HttpClient(CIO) {
        // As upstream: a redirect is never followed, so a server's token is not carried to another host.
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        if (bindProxy != null && bindAuthorization != null) {
            engine { proxy = bindProxy }
            // Ktor CIO forwards a request-level Proxy-Authorization; it never derives one from
            // the proxy URL's userinfo.
            install(createClientPlugin("VpnBindProxyAuth") {
                on(Send) { request ->
                    request.headers["Proxy-Authorization"] = bindAuthorization
                    proceed(request)
                }
            })
        }
    }
}
