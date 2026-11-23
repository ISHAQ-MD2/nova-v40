package com.nova.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Bridge between NOVA Assistant / NovaBrowser and Google Chrome.
 * - Opens URLs or search queries via Chrome Custom Tabs with ACTION_VIEW fallback.
 * - Parses incoming nova://assistant deep links from Chrome or the companion extension.
 */
object ChromeBridge {
    private const val CHROME_PKG = "com.android.chrome"

    data class DeepLinkRequest(
        val action: String,
        val text: String,
        val url: String
    )

    /** Normalize a URL or plain search text into a properly encoded https URL. */
    fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "https://www.google.com"
        val candidate = when {
            t.startsWith("http://", ignoreCase = true) ||
                t.startsWith("https://", ignoreCase = true) -> t
            t.contains(' ') || !t.contains('.') -> searchUrl(t)
            else -> "https://$t"
        }
        return encodeUrl(candidate)
    }

    fun searchUrl(query: String): String {
        val q = query.trim()
        return if (q.isEmpty()) "https://www.google.com"
        else "https://www.google.com/search?q=" + Uri.encode(q)
    }

    private fun encodeUrl(url: String): String {
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme
        if (scheme.isNullOrBlank()) return url.replace(" ", "%20")
        return try {
            // Re-encode path/query/fragment safely while keeping valid URI structure intact
            Uri.Builder()
                .scheme(scheme.lowercase())
                .encodedAuthority(parsed.encodedAuthority)
                .encodedPath(parsed.encodedPath?.replace(" ", "%20"))
                .encodedQuery(parsed.encodedQuery?.replace(" ", "%20"))
                .encodedFragment(parsed.encodedFragment?.replace(" ", "%20"))
                .build()
                .toString()
        } catch (_: Exception) {
            url.replace(" ", "%20")
        }
    }

    /** Open the given URL or query in Chrome Custom Tabs, falling back to ACTION_VIEW. */
    fun openInChrome(context: Context, url: String) {
        val targetUrl = normalizeUrl(url)
        val uri = Uri.parse(targetUrl)

        val customTabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        if (context !is Activity) {
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Prefer Chrome Custom Tabs first
        try {
            customTabs.intent.setPackage(CHROME_PKG)
            customTabs.launchUrl(context, uri)
            return
        } catch (_: Throwable) {
            customTabs.intent.setPackage(null)
        }

        // Retry with any available Custom Tabs provider
        try {
            customTabs.launchUrl(context, uri)
            return
        } catch (_: Throwable) {
            // Fall through to standard ACTION_VIEW fallback
        }

        // Final fallback to ACTION_VIEW (Chrome first, then default browser)
        val viewChrome = Intent(Intent.ACTION_VIEW, uri).setPackage(CHROME_PKG)
        if (context !is Activity) viewChrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(viewChrome)
            return
        } catch (_: Throwable) {
            // Try generic browser
        }

        val viewAny = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) viewAny.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(viewAny)
        } catch (_: Throwable) {
        }
    }

    fun searchInChrome(context: Context, query: String) {
        openInChrome(context, searchUrl(query))
    }

    /** Parse nova://assistant?action=ask&text=...&url=... deep links. */
    fun parseDeepLink(intent: Intent?): DeepLinkRequest? {
        val uri = intent?.data ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "nova" || host != "assistant") return null

        val action = uri.getQueryParameter("action")?.trim().orEmpty().ifEmpty { "ask" }
        val text = uri.getQueryParameter("text")?.trim().orEmpty()
        val url = uri.getQueryParameter("url")?.trim().orEmpty()
        return DeepLinkRequest(action = action, text = text, url = url)
    }

    /** Parse either a nova://assistant deep link or an Android ACTION_SEND share from mobile Chrome. */
    fun parseIncomingIntent(intent: Intent?): DeepLinkRequest? {
        parseDeepLink(intent)?.let { return it }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            if (shared.isBlank() && subject.isBlank()) return null
            val isUrlOnly = shared.startsWith("http://", true) || shared.startsWith("https://", true)
            return if (isUrlOnly) {
                DeepLinkRequest(action = "ask", text = subject, url = shared)
            } else {
                val combined = if (subject.isNotBlank() && !shared.contains(subject)) "$subject\n$shared" else shared
                DeepLinkRequest(action = "ask", text = combined, url = "")
            }
        }
        return null
    }

    /** Format parsed deep link parameters into a prompt for NOVA's assistant input. */
    fun buildAssistantPrompt(req: DeepLinkRequest): String = when {
        req.text.isNotBlank() && req.url.isNotBlank() -> "${req.text}\n${req.url}"
        req.text.isNotBlank() -> req.text
        req.url.isNotBlank() -> "Is page ke baare mein madad karo: ${req.url}"
        else -> ""
    }
}
