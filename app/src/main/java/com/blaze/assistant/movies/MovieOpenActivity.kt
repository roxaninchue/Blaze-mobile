package com.blaze.assistant.movies

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns

/**
 * 0.46.2 "Open with Blaze" for a video file from another app (Files, a browser download, a share). The only exported entry for
 * Movies: it reads just the video URI (no extras are trusted), and hands it to Blaze Video, which plays it and keeps its place.
 */
class MovieOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null && uri.scheme in setOf("content", "file", "http", "https")) {
            Movies.init(this)
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            com.blaze.assistant.mediaapps.VideoActivity.openMovie(this, play(uri, name(uri)))
        }
        finish()
    }

    private fun name(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        fun play(uri: Uri, name: String): MoviePlay {
            val title = name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Video" }.take(120)
            return MoviePlay("local:" + uri, title, uri.toString(), "", "local", ref = "local:$uri")
        }
    }
}
