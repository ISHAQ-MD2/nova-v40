package com.nova.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight host-based WebView ad & tracker blocker for NOVA Browser.
 */
object AdBlocker {
    private val BLOCKED_HOSTS = hashSetOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "scorecardresearch.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "adnxs.com",
        "rubiconproject.com",
        "openx.net",
        "adsafeprotected.com",
        "moatads.com",
        "amazon-adsystem.com",
        "admob.com",
        "quantserve.com",
        "mgid.com",
        "revcontent.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "bidswitch.net",
        "casalemedia.com",
        "sharethrough.com",
        "media.net",
        "zedo.com",
        "ads-twitter.com",
        "an.facebook.com"
    )

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { Uri.parse(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        var cur = host
        while (true) {
            if (cur in BLOCKED_HOSTS) return true
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        return false
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
