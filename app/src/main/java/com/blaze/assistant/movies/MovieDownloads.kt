package com.blaze.assistant.movies

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.blaze.assistant.mediaapps.Downloads
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem

/**
 * 0.46.2 Movies downloads. Internet Archive / Blender files go through Blaze's own download queue (resumable, verified, saved to
 * Videos); other direct files (Jellyfin, a provider's mp4) are queued with Android's DownloadManager (with the source's headers).
 * Adaptive streams (HLS / DASH) can't be saved as one file and say so.
 */
object MovieDownloads {
    /** null when it can be downloaded, else why not. */
    fun why(s: MovieStream): String? = when (s.type.lowercase()) {
        "m3u8", "hls", "dash", "mpd" -> "This stream can't be saved as a file"
        else -> if (!s.link.startsWith("http", true)) "Already on this phone" else null
    }

    fun start(ctx: Context, src: MovieSource, play: MoviePlay, s: MovieStream): String {
        why(s)?.let { return it }
        if (src.id == "archive" || src.id == "blender") {
            val id = play.ref.removePrefix("archive:")
            val name = Uri.parse(s.link).lastPathSegment.orEmpty().ifBlank { "$id.mp4" }
            val ext = name.substringAfterLast('.', "mp4").lowercase()
            val f = StoreFormat(name, s.link, if (ext == "ogv") "video/ogg" else "video/mp4", contentLength = s.bytes, hasAudio = true, hasVideo = true,
                height = MovieJson.qualityRank(s.quality), name = play.title)
            val item = StoreItem("archive-films", id, play.title, "", thumb = play.image, licence = if (src.id == "blender") "CC BY" else "Public domain")
            val d = StoreDetail(id, play.title, "", 0, play.image, listOf(f), item.licence, "")
            Downloads.add(ctx, item, d, Choice("f:$name", s.quality.ifBlank { "Video" }, ext, listOf(name), ext, s.bytes, height = f.height), "video")
            return "Downloading \u201c${play.title}\u201d to Videos."
        }
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return "Downloads aren't available on this phone"
        val file = (play.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { "video" }.take(80)) + "." + s.type.lowercase().ifBlank { "mp4" }.take(5)
        val r = DownloadManager.Request(Uri.parse(s.link)).setTitle(play.title).setDescription("Blaze Movies")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val pub = try { r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Blaze/$file"); true }
            catch (_: Exception) { r.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, file); false }
        s.headers.forEach { (k, v) -> runCatching { r.addRequestHeader(k, v) } }
        return try { dm.enqueue(r); "Downloading \u201c${play.title}\u201d to " + (if (pub) "Movies/Blaze." else "Blaze's Movies folder.") }
            catch (e: Exception) { "Couldn't start the download: ${e.message}" }
    }
}
