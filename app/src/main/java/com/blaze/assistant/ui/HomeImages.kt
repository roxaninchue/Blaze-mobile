package com.blaze.assistant.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * v0.29.3: small preview pictures for Home cards (news, videos). Downscaled to ~360 px wide on decode, kept in a 6 MB
 * memory cache and a 15 MB disk cache, https only, at most 3 downloads at once. Low-RAM phones still get them because
 * nothing bigger than a thumbnail is ever decoded.
 */
object HomeImages {
    private val mem = object : android.util.LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * bytesPerPixel
    }
    /** 0.46.6: low-RAM phones (0.46.5 crash report, hundreds of memory trims a day) keep 2 MB of decoded pictures, RGB_565. */
    @Volatile var lowRam = false; private set
    private val bytesPerPixel: Int get() = 2
    fun cacheBytes(low: Boolean): Int = if (low) 2 * 1024 * 1024 else 6 * 1024 * 1024
    fun configure(low: Boolean) { lowRam = low; try { mem.resize(cacheBytes(low)) } catch (_: Throwable) { } }
    /** Memory is short: drop every decoded picture (the disk copies stay). */
    fun trim() { try { mem.evictAll() } catch (_: Throwable) { } }
    /** Thumbnail width: 280 px on low-RAM phones, else 360. */
    fun target(): Int = if (lowRam) 280 else 360
    private val gate = kotlinx.coroutines.sync.Semaphore(3)
    private val locks = HashMap<String, Mutex>()

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "home-img").apply { mkdirs() }

    /** Inexact-power-of-two sample size so the decoded width is about [target] px. */
    fun sample(width: Int, target: Int = 360): Int { var s = 1; while (width / (s * 2) >= target) s *= 2; return s }

    /**
     * 0.46.5 (crash hardening for posters from user-added sources): the sample size for a [w] x [h] picture so neither side
     * is much over a thumbnail (width ~[target], height <= 3 x target) and it stays under ~1.5 MP; -1 = don't decode at
     * all (unknown size, or a strip more than 8:1 that would only draw as a sliver).
     */
    fun safeSample(w: Int, h: Int, target: Int = 360): Int {
        if (w <= 0 || h <= 0) return -1
        if (w.toLong() > h.toLong() * 8 || h.toLong() > w.toLong() * 8) return -1
        var s = sample(w, target)
        while (h / s > target * 3 || (w.toLong() / s) * (h.toLong() / s) > 1_500_000L) s *= 2
        return s
    }
    const val MAX_BYTES = 4_000_000L

    suspend fun load(ctx: Context, url: String): ImageBitmap? {
        if (!url.startsWith("https://")) return null
        mem.get(url)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            mem.get(url) ?: withContext(Dispatchers.IO) {
                val f = File(dir(ctx), key(url))
                try {
                    if (!f.isFile || f.length() == 0L) gate.acquire().let {
                        try {
                            val c = URL(url).openConnection() as HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = true
                            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Blaze")
                            if (c.responseCode !in 200..299) return@withContext null
                            val tmp = File(f.path + ".part")
                            // 0.46.5: stop reading past the cap (a huge "poster" never fills the disk or memory)
                            if (c.contentLengthLong > MAX_BYTES) { c.disconnect(); return@withContext null }
                            var big = false
                            c.inputStream.use { i -> tmp.outputStream().use { o ->
                                val buf = ByteArray(16 * 1024); var total = 0L
                                while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) { big = true; break }; o.write(buf, 0, n) }
                            } }
                            if (big || tmp.length() > MAX_BYTES) { tmp.delete(); return@withContext null }
                            tmp.renameTo(f)
                        } finally { gate.release() }
                    }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    val ss = safeSample(o.outWidth, o.outHeight, target())
                    if (ss <= 0) { f.delete(); return@withContext null }
                    val bmp = try { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }) }
                        catch (_: OutOfMemoryError) { null } ?: return@withContext null
                    trim(dir(ctx))
                    bmp.asImageBitmap().also { mem.put(url, it) }
                } catch (_: Throwable) { null }
            }
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total < 15_000_000) return
        for (f in files.sortedBy { it.lastModified() }) { if (total < 10_000_000) break; total -= f.length(); f.delete() }
    }
}

/** The picture at [url], or null while it loads / when it can't. */
@Composable
fun rememberHomeImage(url: String): ImageBitmap? {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, url) { value = if (url.isBlank()) null else HomeImages.load(ctx, url) }
    return img
}
