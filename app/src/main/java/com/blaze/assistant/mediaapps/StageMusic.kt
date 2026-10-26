package com.blaze.assistant.mediaapps

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 0.46.0 Blaze Stage: music plays IN PLACE. The same MusicService (foreground media service, lock-screen controls) runs it,
 * but nothing opens the Music screen: the service is started in the background and the Stage shows the music hero / mini
 * player. "Open in full view" still opens Blaze Music.
 */
object StageMusic {
    data class Now(val service: Boolean, val playing: Boolean, val title: String, val artist: String, val posMs: Long, val durMs: Long, val queue: Int)

    /** Start the music service without any screen (it plays pendingPlay / pendingResume as soon as it's up). Main thread. */
    fun startQuiet(ctx: Context): Boolean = try { ctx.startService(Intent(ctx, MusicService::class.java)); true } catch (_: Exception) { false }

    /** Main thread. */
    fun now(): Now {
        val s = MusicService.current ?: return Now(false, false, "", "", 0L, 0L, 0)
        return try { Now(true, s.isPlayingNow(), s.nowTitle(), s.nowArtist(), s.positionMs(), s.durationMs(), s.queueSize()) } catch (_: Exception) { Now(true, false, "", "", 0L, 0L, 0) }
    }

    /** 0.46.1 the whole card state (queue, shuffle, repeat, position). Main thread. */
    fun snapshot(): StageMusicLogic.Snapshot = MusicService.current?.let { try { it.snapshot() } catch (_: Exception) { null } } ?: StageMusicLogic.Snapshot()

    /**
     * play | pause | next | previous | shuffle | repeat | seek:<ms> | at:<i> | up:<i> | down:<i> | remove:<i> | next_up:<i>
     * (main thread). All of it goes to the one MusicService, so the notification, lock screen and widget follow.
     */
    fun op(ctx: Context, op: String) {
        val s = MusicService.current
        if (s == null) { if (op == "play") { MusicService.pendingResume = true; startQuiet(ctx) }; return }
        val (cmd, arg) = StageMusicLogic.parse(op) ?: return
        when (cmd) {
            "shuffle" -> s.setShuffle(!s.shuffleOn())
            "repeat" -> s.cycleRepeat()
            "seek" -> arg?.let { s.seekTo(it) }
            "at" -> arg?.let { s.playAt(it.toInt()) }
            "up" -> arg?.let { s.moveItem(it.toInt(), it.toInt() - 1) }
            "down" -> arg?.let { s.moveItem(it.toInt(), it.toInt() + 1) }
            "remove" -> arg?.let { s.removeAt(it.toInt()) }
            "next_up" -> arg?.let { s.playNext(it.toInt()) }
            else -> s.transport(cmd)
        }
    }

    /**
     * Start music for the Stage: [query] = a song / artist / mix name, or blank = resume the last queue, else Blaze DJ picks.
     * Returns a plain line for the tool result.
     */
    suspend fun play(ctx: Context, query: String): String {
        withContext(Dispatchers.IO) { MediaLib.ensure(ctx) }
        val songs = MediaLib.songs.value
        val q = query.trim()
        val keys: List<String> = when {
            q.isNotEmpty() -> {
                val lists = MusicSmart.dailyMixes(songs, MediaLib.plays.value, System.currentTimeMillis() / 86_400_000L) +
                    MusicSmart.smartLists(songs, MediaLib.plays.value, MediaLib.liked.value, System.currentTimeMillis())
                lists.firstOrNull { it.name.equals(q, true) }?.keys
                    ?: songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.map { it.key }.ifEmpty { null }
                    ?: emptyList()
            }
            else -> emptyList()
        }
        return withContext(Dispatchers.Main) {
            val svc = MusicService.current
            when {
                keys.isNotEmpty() -> { if (svc != null) svc.playKeys(keys, false) else { MusicService.pendingPlay = keys to false; startQuiet(ctx) }
                    "Playing ${keys.size} song${if (keys.size == 1) "" else "s"} for \u201c$q\u201d on the Stage." }
                svc != null && svc.queueSize() > 0 -> { svc.transport("play"); "Playing ${svc.nowTitle().ifBlank { "the queue" }} on the Stage." }
                svc == null && (try { withContext(Dispatchers.IO) { MediaLib.loadQueue()?.first?.size ?: 0 } } catch (_: Exception) { 0 }) > 0 ->
                    { MusicService.pendingResume = true; startQuiet(ctx); "Resuming the last queue on the Stage." }
                songs.isNotEmpty() -> {
                    val picks = MusicSmart.djPicks(songs, MediaLib.plays.value, MediaLib.liked.value.toSet(), MediaLib.recent.value, 10, System.nanoTime()).map { it.key }
                    if (svc != null) svc.playKeys(picks, false) else { MusicService.pendingPlay = picks to false; startQuiet(ctx) }
                    if (q.isNotEmpty()) "No song matched \u201c$q\u201d; playing Blaze DJ picks on the Stage instead." else "Playing Blaze DJ picks on the Stage."
                }
                else -> "There are no songs in Blaze Music yet: the music card is on the Stage with a button to the Music store (media_download can fetch songs)."
            }
        }
    }
}
