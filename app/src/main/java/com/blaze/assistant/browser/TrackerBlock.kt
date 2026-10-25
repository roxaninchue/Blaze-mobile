package com.blaze.assistant.browser

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * v0.45.25 "Block ads & trackers" (Sky launcher toggle). Off by default so no site changes until the user turns it on.
 * Only third-party sub-resources (scripts, pixels, frames) from well-known ad / analytics hosts are refused; the page
 * itself always loads. The host match is pure (TrackerBlockTest).
 */
object TrackerBlock {
    /** Registrable ad / analytics hosts. A request to one of them, or any subdomain, is refused. */
    val HOSTS: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "adservice.google.com",
        "app-measurement.com", "scorecardresearch.com", "quantserve.com", "quantcount.com", "adnxs.com", "adsrvr.org",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com", "advertising.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "moatads.com", "adform.net", "bidswitch.net", "smartadserver.com",
        "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "chartbeat.com", "chartbeat.net", "newrelic.com", "nr-data.net", "bugsnag.com", "branch.io", "adjust.com",
        "appsflyer.com", "kochava.com", "yieldmo.com", "teads.tv", "33across.com", "sharethrough.com", "media.net",
        "zedo.com", "adroll.com", "demdex.net", "omtrdc.net", "everesttech.net", "krxd.net", "bluekai.com", "exelator.com",
        "agkn.com", "mathtag.com", "rlcdn.com", "tapad.com", "addthis.com", "imrworldwide.com", "yandex-metrica.com",
        "mc.yandex.ru", "ads-twitter.com", "analytics.twitter.com", "ads.linkedin.com", "px.ads.linkedin.com",
        "analytics.tiktok.com", "ads.tiktok.com", "connect.facebook.net", "pixel.facebook.com", "an.facebook.com",
    )

    /** True when [host] is a listed tracker or a subdomain of one. */
    fun blocks(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        if (h.isEmpty()) return false
        while (true) {
            if (h in HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false   // stop at the registrable-ish last two labels
            h = h.substring(dot + 1)
        }
    }

    var enabled by mutableStateOf(false); private set
    /** Requests refused since the toggle went on (kept across launches). */
    var total by mutableIntStateOf(0); private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("blaze_browser_trackers", Context.MODE_PRIVATE)
        prefs = p; enabled = p.getBoolean("on", false); total = p.getInt("total", 0)
    }

    fun setOn(on: Boolean) { enabled = on; prefs?.edit()?.putBoolean("on", on)?.apply() }

    /** Main thread: one more refused; the total is written now and then, not on every request. */
    fun counted() { total++; if (total % 20 == 0) prefs?.edit()?.putInt("total", total)?.apply() }
    fun flush() { prefs?.edit()?.putInt("total", total)?.apply() }

    /** The empty answer a refused request gets. */
    fun empty(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
}
