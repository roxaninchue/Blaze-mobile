package com.blaze.assistant.movies

/**
 * 0.46.5: friendly details for any source. Provider link / group titles are raw release names
 * ("Season 2 [English - Japanese] 720p x264 [200MB/E]"); this reads them generically into season, episode, quality,
 * languages and size, and groups a title's links into seasons with quality / language variants. Nothing here knows any
 * provider. Pure (ReleaseTitleTest).
 */
object ReleaseTitle {
    data class Info(val season: Int?, val episode: Int?, val height: Int, val languages: List<String>, val size: String, val clean: String) {
        /** "4K", "1080p", "720p", "480p" or "" */
        val quality: String get() = qualityLabel(height)
    }

    fun qualityLabel(h: Int): String = when { h >= 2000 -> "4K"; h > 0 -> "${h}p"; else -> "" }

    /** Language names a release title may carry (generic list; matched as whole words, any case). */
    val LANGS = listOf("English", "Hindi", "Japanese", "Korean", "Chinese", "Mandarin", "Cantonese", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali",
        "Marathi", "Punjabi", "Urdu", "Spanish", "French", "German", "Italian", "Portuguese", "Russian", "Arabic", "Turkish", "Thai", "Indonesian",
        "Filipino", "Vietnamese", "Swahili", "Dutch", "Polish", "Persian")
    private val langAbbr = mapOf("eng" to "English", "hin" to "Hindi", "jap" to "Japanese", "jpn" to "Japanese", "kor" to "Korean", "tam" to "Tamil",
        "tel" to "Telugu", "spa" to "Spanish", "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese")

    private val seasonRe = Regex("""(?i)\b(?:season|series|saison|temporada)\s*0*(\d{1,3})\b|\bS(\d{1,2})(?=E\d|\b)""")
    private val episodeRe = Regex("""(?i)\b(?:episode|ep|e)\s*\.?\s*0*(\d{1,4})\b|S\d{1,2}E(\d{1,4})""")
    private val heightRe = Regex("""(?i)\b(2160|1440|1080|720|576|480|360|240)\s*p?\b""")
    private val fourK = Regex("""(?i)\b(?:4k|uhd)\b""")
    private val sizeRe = Regex("""(?i)(\d+(?:\.\d+)?)\s*(GB|MB)(\s*/\s*(?:E|EP|episode))?""")
    private val junk = Regex("""(?i)\[[^\]]*]|\([^)]*\)|\b(?:x264|x265|h\.?264|h\.?265|hevc|10bit|web-?dl|web-?rip|webrip|bluray|blu-ray|brrip|hdrip|dvdrip|hdtv|aac|ddp?5\.1|esub|esubs|dual audio|multi audio)\b|\b\d{3,4}p\b|\b4k\b""")

    fun parse(raw: String): Info {
        val t = raw.replace('\u2013', '-').replace('\u2014', '-').replace('_', ' ')
        val s = seasonRe.find(t)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() }
        val e = episodeRe.find(t)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() }
        val h = if (fourK.containsMatchIn(t)) 2160 else heightRe.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val langs = LinkedHashSet<String>()
        for (l in LANGS) if (Regex("""(?i)\b${l}\b""").containsMatchIn(t)) langs += l
        Regex("""[A-Za-z]{3}""").findAll(t).forEach { m -> langAbbr[m.value.lowercase()]?.let { if (Regex("""(?i)(?<![A-Za-z])${m.value}(?![A-Za-z])""").containsMatchIn(t)) langs += it } }
        val size = sizeRe.find(t)?.let { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() + if (m.groupValues[3].isNotBlank()) " per episode" else "" }.orEmpty()
        var clean = junk.replace(t, " ")
        clean = sizeRe.replace(clean, " ")
        for (l in langs) clean = clean.replace(Regex("""(?i)\b$l\b"""), " ")
        clean = clean.replace(Regex("""[|·•\-]+\s*$"""), " ").replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', '\u00b7', ' ', '.')
        return Info(s, e, h, langs.toList(), size, clean)
    }

    /** One way to watch a season (or a film): a quality / language / size variant, backed by one source group. */
    data class Variant(val group: MovieGroup, val index: Int, val height: Int, val languages: List<String>, val size: String) {
        val label: String get() = listOf(qualityLabel(height), languages.joinToString(", "), size).filter { it.isNotBlank() }.joinToString(" \u00b7 ").ifBlank { "Option ${index + 1}" }
    }
    data class Season(val number: Int?, val variants: List<Variant>) {
        val label: String get() = if (number == null) "Watch" else "Season $number"
    }

    /** The groups of a title as seasons (by the season number in the group title; no number = one "Watch" season), each with its variants. */
    fun seasons(groups: List<MovieGroup>): List<Season> {
        val parsed = groups.mapIndexed { i, g ->
            val p = parse(g.title + " " + g.quality)
            Variant(g, i, if (p.height > 0) p.height else MovieJson.qualityRank(g.quality), p.languages, p.size) to p.season
        }
        val bySeason = LinkedHashMap<Int?, MutableList<Variant>>()
        for ((v, s) in parsed) bySeason.getOrPut(s) { ArrayList() } += v
        val numbered = bySeason.filterKeys { it != null }.toSortedMap(compareBy { it!! }).map { (k, v) -> Season(k, v) }
        val loose = bySeason[null]?.let { listOf(Season(null, it)) }.orEmpty()
        return if (numbered.isEmpty()) loose else numbered + loose
    }

    /**
     * The variant to use by default: the remembered quality / language when there is one, else the best quality at or under
     * [maxHeight] (data saver), else the smallest one.
     */
    fun pick(variants: List<Variant>, maxHeight: Int, prefHeight: Int = 0, prefLang: String = ""): Variant? {
        if (variants.isEmpty()) return null
        val langOk = variants.filter { prefLang.isBlank() || it.languages.isEmpty() || it.languages.any { l -> l.equals(prefLang, true) } }.ifEmpty { variants }
        if (prefHeight > 0) langOk.firstOrNull { it.height == prefHeight }?.let { return it }
        val under = langOk.filter { it.height in 1..maxHeight }
        return under.maxByOrNull { it.height } ?: langOk.filter { it.height > 0 }.minByOrNull { it.height } ?: langOk.first()
    }

    /** An episode row: number, a readable title (raw filename hidden), its thumbnail. */
    data class Episode(val number: Int?, val title: String, val link: MovieLink)
    fun episodes(links: List<MovieLink>): List<Episode> = links.mapIndexed { i, l ->
        val p = parse(l.title)
        val n = p.episode ?: if (links.size > 1) i + 1 else null
        val title = p.clean.replace(Regex("""(?i)^(?:episode|ep|e)\s*\d+\s*[:\-.]?\s*"""), "").replace(Regex("""(?i)\bS\d{1,2}E\d{1,4}\b"""), "").trim()
        Episode(n, title.ifBlank { if (n != null) "Episode $n" else "Play" }.let { if (it.equals("play", true) && n != null) "Episode $n" else it }, l)
    }
}
