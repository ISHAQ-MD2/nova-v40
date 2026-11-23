package com.nova.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Network helper. "Unable to resolve host" ka ilaaj:
 * 1) pehle phone ka apna DNS, 2) fail ho to Google/Cloudflare DNS-over-HTTPS (seedha IP se, DNS ki zaroorat nahi).
 */
object Net {
    private val base = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    private val doh: List<DnsOverHttps> = listOf(
        DnsOverHttps.Builder().client(base).url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))).build(),
        DnsOverHttps.Builder().client(base).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))).build()
    )

    val fallbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try { return Dns.SYSTEM.lookup(hostname) } catch (_: UnknownHostException) {}
            var last: Exception? = null
            for (d in doh) {
                try { val r = d.lookup(hostname); if (r.isNotEmpty()) return r } catch (e: Exception) { last = e }
            }
            throw UnknownHostException("DNS fail: $hostname (${last?.message ?: "no result"})")
        }
    }

    fun client(pingSec: Long = 0, readTimeoutSec: Long = 90): OkHttpClient {
        val b = OkHttpClient.Builder().dns(fallbackDns)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        if (pingSec > 0) b.pingInterval(pingSec, TimeUnit.SECONDS)
        return b.build()
    }

    fun online(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) { true }
    }

    /** Insaan ko samajh aane wala error. */
    fun friendly(ctx: Context, raw: String): String {
        val r = raw.lowercase()
        return when {
            !online(ctx) -> "Internet band hai - WiFi/Mobile data ON karo (Airplane mode OFF)"
            "resolve host" in r || "dns" in r || "no address" in r ->
                "Internet to hai par Google server nahi mil raha. Settings > Private DNS ko 'Off' ya 'Automatic' karo, phir dobara Start karo. ($raw)"
            "timeout" in r || "timed out" in r -> "Server ne jawab nahi diya - net slow hai, dobara try ho raha hai"
            "403" in r || "401" in r || "400" in r && "key" in r -> "API key galat/band hai - Setup mein nayi key daalo"
            "429" in r -> "Gemini ki limit khatam (429) - thori der baad try karo"
            else -> raw
        }
    }
}
