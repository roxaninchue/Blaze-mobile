package com.blaze.assistant.mediaapps

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random

/**
 * 0.46.4 Blaze DJ: picks and orders songs from the user's own library (plays, likes, recent plays, time of day and a
 * vibe), with short optional spoken intros through the phone's text-to-speech. Off on battery saver. Speech is plain:
 * the song and artist, no metaphor filler (0.46.2 Halo rule, checked by [DjSpeech.plain] and MusicRevampTest).
 */
object DjPlan {
    enum class Vibe(val id: String, val label: String) {
        AUTO("auto", "Auto"), CHILL("chill", "Chill"), FOCUS("focus", "Focus"), UPBEAT("upbeat", "Upbeat"), LATE("late", "Late night");
        companion object { fun of(id: String): Vibe = values().firstOrNull { it.id == id } ?: AUTO }
    }

    /** Words in a title or album that hint at a vibe (all we know without audio analysis). */
    private val words: Map<Vibe, Regex> = mapOf(
        Vibe.CHILL to Regex("""\b(chill|lo-?fi|acoustic|slow|soft|calm|ambient|sleep|unplugged|lullaby|mellow|rain)\b""", RegexOption.IGNORE_CASE),
        Vibe.FOCUS to Regex("""\b(instrumental|piano|study|focus|classical|ambient|beats|sonata|concerto|etude|nocturne)\b""", RegexOption.IGNORE_CASE),
        Vibe.UPBEAT to Regex("""\b(remix|dance|party|club|edit|mix|workout|run|banger|anthem|amapiano|afrobeats?)\b""", RegexOption.IGNORE_CASE),
        Vibe.LATE to Regex("""\b(night|midnight|late|moon|dark|slow|r&b|rnb|soul|after hours)\b""", RegexOption.IGNORE_CASE),
    )

    /** The vibe Auto means at this hour: upbeat in the morning and afternoon, focus late morning, chill evening, late night after 22. */
    fun vibeFor(hour: Int): Vibe = when (hour) {
        in 6..9 -> Vibe.UPBEAT
        in 10..12 -> Vibe.FOCUS
        in 13..17 -> Vibe.UPBEAT
        in 18..21 -> Vibe.CHILL
        else -> Vibe.LATE
    }

    fun partOfDay(hour: Int): String = when (hour) { in 5..11 -> "this morning"; in 12..16 -> "this afternoon"; in 17..21 -> "this evening"; else -> "tonight" }

    private fun vibeScore(t: Track, v: Vibe): Double {
        val text = t.title + " " + t.album
        var s = 0.0
        if (words[v]?.containsMatchIn(text) == true) s += 4.0
        val min = t.durationMs / 60_000.0
        when (v) {
            Vibe.CHILL, Vibe.LATE -> if (min >= 4.0) s += 0.6
            Vibe.FOCUS -> if (min >= 5.0) s += 1.0
            Vibe.UPBEAT -> if (min in 2.0..4.0) s += 0.6
            Vibe.AUTO -> {}
        }
        return s
    }

    /**
     * [n] songs in play order: weighted by plays and likes, nudged towards the vibe (Auto = the hour's vibe), never one of
     * the last 20 played (unless the library is that small), no repeats, and no artist twice in a row when it can be avoided.
     */
    fun sequence(songs: List<Track>, events: List<PlayEvent>, liked: Set<String>, recent: List<String>, vibe: Vibe, hour: Int, n: Int, seed: Long): List<Track> {
        if (songs.isEmpty() || n <= 0) return emptyList()
        val v = if (vibe == Vibe.AUTO) vibeFor(hour) else vibe
        val st = MusicSmart.stats(events)
        val avoid = recent.take(20).toSet()
        val rnd = Random(seed)
        val pool = songs.filter { it.kind != "video" }.distinctBy { it.key }.filter { it.key !in avoid }.ifEmpty { songs.distinctBy { it.key } }
        val ranked = pool.map { t ->
            val s = st[t.key]
            val w = 1.0 + (s?.plays ?: 0) * 1.5 + (if (t.key in liked) 5 else 0) + vibeScore(t, v) * 2
            t to w * (0.6 + rnd.nextDouble())
        }.sortedByDescending { it.second }.map { it.first }.take(n * 2)
        // Space out artists: take the best song whose artist differs from the last one's, when there is one.
        val left = ranked.toMutableList()
        val out = ArrayList<Track>()
        while (out.size < n && left.isNotEmpty()) {
            val last = out.lastOrNull()?.artist?.lowercase()
            val i = left.indexOfFirst { last.isNullOrBlank() || it.artist.lowercase() != last }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(i)
        }
        return out
    }

    /** "More like this": the same artist first, then the same album, then the songs most played alongside, never [seed] itself. */
    fun moreLikeThis(seed: Track, songs: List<Track>, events: List<PlayEvent>, recent: List<String>, n: Int): List<Track> {
        val st = MusicSmart.stats(events)
        val avoid = recent.take(10).toSet() + seed.key
        val pool = songs.filter { it.key !in avoid && it.kind != "video" }
        val sameArtist = pool.filter { seed.artist.isNotBlank() && it.artist.equals(seed.artist, true) }
        val sameAlbum = pool.filter { seed.album.isNotBlank() && it.album.equals(seed.album, true) }
        val rest = pool.sortedByDescending { st[it.key]?.plays ?: 0 }
        return (sameArtist.shuffled(Random(seed.key.hashCode())) + sameAlbum + rest).distinctBy { it.key }.take(n)
    }
}

object DjSpeech {
    /** Filler the DJ must never say (Halo rule 0.46.2): imagery, metaphors, hype words. Same spirit as WaitNudge.poetic. */
    val filler = Regex("""\b(?:journey|vibe ?check|soundscape|sonic|tapestr\w*|embark\w*|dive in|magic\w*|soul ?food|weav\w*|sprinkl\w*|ride|buckle up|sit back|fire|banger|groove (?:train|machine)|melod(?:ic )?adventure|let'?s go|get ready|imagine|picture this|think of it|like a \w+|golden|vibes?)\b""", RegexOption.IGNORE_CASE)
    fun plain(line: String): Boolean = !filler.containsMatchIn(line)

    private fun who(t: Track): String = if (t.artist.isBlank()) t.title else "${t.title} by ${t.artist}"

    /** The first intro: what the DJ is doing and the first song, in two short sentences. */
    fun start(first: Track, vibe: DjPlan.Vibe, hour: Int): String {
        val v = if (vibe == DjPlan.Vibe.AUTO) DjPlan.vibeFor(hour) else vibe
        return "Blaze DJ is on, playing ${v.label.lowercase()} songs from your library. First up: ${who(first)}."
    }

    /** Between songs: one short sentence. */
    fun next(t: Track): String = "Next: ${who(t)}."
    fun changed(v: DjPlan.Vibe): String = "Switching to ${v.label.lowercase()} songs."
    fun moreLike(t: Track): String = "More songs like ${t.title}."
}

/** The DJ at runtime: on / off, the vibe, whether it speaks, and its own text-to-speech (ducks the music while it talks). */
object BlazeDj {
    const val VIBE = "music_dj_vibe"
    const val VOICE = "music_dj_voice"
    val on = MutableStateFlow(false)
    val vibe = MutableStateFlow(DjPlan.Vibe.AUTO)
    @Volatile var duck = 1f   // MusicService's fade tick multiplies the volume by this while the DJ speaks

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    fun hour(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    fun voiceOn(ctx: Context): Boolean = MediaPrefs.bool(ctx, VOICE, true)
    fun setVoice(ctx: Context, v: Boolean) = MediaPrefs.setBool(ctx, VOICE, v)
    fun lowPower(ctx: Context): Boolean = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode }.getOrDefault(false)
    /** Spoken intros only when the user left them on and the phone isn't saving battery. */
    fun speaks(ctx: Context): Boolean = voiceOn(ctx) && !lowPower(ctx)

    fun load(ctx: Context) {
        on.value = MediaPrefs.bool(ctx, MediaPrefs.DJ, false)
        vibe.value = DjPlan.Vibe.of(MediaPrefs.str(ctx, VIBE, "auto"))
    }

    fun picks(n: Int, exclude: Set<String> = emptySet()): List<Track> = DjPlan.sequence(MediaLib.songs.value.filter { it.key !in exclude }, MediaLib.plays.value,
        MediaLib.liked.value.toSet(), MediaLib.recent.value, vibe.value, hour(), n, System.nanoTime())

    fun setVibe(ctx: Context, v: DjPlan.Vibe) { vibe.value = v; MediaPrefs.setStr(ctx, VIBE, v.id) }

    fun say(ctx: Context, text: String) {
        if (!speaks(ctx)) return
        val app = ctx.applicationContext
        val t = tts
        if (t != null && ttsReady) { speakNow(t, text); return }
        if (t != null) return
        tts = android.speech.tts.TextToSpeech(app) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) { duck = 0.3f }
                override fun onDone(id: String?) { duck = 1f }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { duck = 1f }
            })
            if (ttsReady) tts?.let { speakNow(it, text) }
        }
    }

    private fun speakNow(t: android.speech.tts.TextToSpeech, text: String) {
        runCatching { t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "dj-" + System.currentTimeMillis()) }
    }

    fun release() { runCatching { tts?.shutdown() }; tts = null; ttsReady = false; duck = 1f }
}
