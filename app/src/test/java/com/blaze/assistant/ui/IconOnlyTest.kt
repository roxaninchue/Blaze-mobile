package com.blaze.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.1 icons, not words (report: the widget's pause drew as an emoji box): no emoji / unicode media glyph may appear anywhere in
 * the app's source (as a character or a \u escape), and the Stage cards draw no text glyphs as icons or buttons at all; they use
 * Material Symbols Rounded vectors with content descriptions.
 */
class IconOnlyTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun kt() = root().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** ⏸ ▶ ⏭ ⏮ ⏹ ⏯ ⏪ ⏩ ⏺ ⏱ 🔁 🔂 🔀 ❚❚ (and their \u escapes, any case). */
    private val mediaChars = listOf("\u23F8", "\u25B6", "\u23ED", "\u23EE", "\u23F9", "\u23EF", "\u23EA", "\u23E9", "\u23FA", "\u23F1",
        "\uD83D\uDD01", "\uD83D\uDD02", "\uD83D\uDD00", "\u275A\u275A")
    private val mediaEscapes = Regex("""\\u(?:23F8|25B6|23ED|23EE|23F9|23EF|23EA|23E9|23FA|23F1|275A)|\\uD83D\\uDD0[012]""", RegexOption.IGNORE_CASE)

    @Test fun noEmojiMediaGlyphsAnywhereInTheApp() {
        val bad = ArrayList<String>()
        for (f in kt()) f.readLines().forEachIndexed { n, line ->
            if (mediaChars.any { line.contains(it) } || mediaEscapes.containsMatchIn(line)) bad += "${f.name}:${n + 1}: ${line.trim().take(120)}"
        }
        assertTrue("Emoji / unicode media glyphs in source (use an Icons.Rounded vector with a content description):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun stageCardsUseVectorIconsWithDescriptions() {
        val files = listOf("ui/StageCards.kt", "ui/StageHost.kt").map { File(root(), "com/blaze/assistant/$it").readText() }
        val glyphText = Regex("""Text\(\s*"(?:\\u[0-9A-Fa-f]{4}|[\u2190-\u2BFF\u2600-\u27BF]|\\uD83[CD]\\uD[C-F][0-9A-Fa-f]{2})+"""")
        for (src in files) {
            assertTrue("a Stage card draws a glyph as text: " + glyphText.find(src)?.value, glyphText.find(src) == null)
            assertTrue(!src.contains("Chip(\"Play\"") && !src.contains("Chip(\"Start\"") && !src.contains("Chip(\"Pause\""))
        }
        val cards = files[0]
        // Every icon button has a description; icons inside buttons are decorative (contentDescription = null on the Icon itself).
        assertTrue(cards.contains("fun StageIconButton(icon: ImageVector, desc: String"))
        assertTrue(Regex("""StageIconButton\(Icons\.""").findAll(cards).count() >= 30)
        // Only the icons the cards use are referenced (R8 strips the rest of material-icons-extended from the release APK).
        assertTrue(!cards.contains("import androidx.compose.material.icons.rounded.*") && !cards.contains("import androidx.compose.material.icons.filled.*"))
    }
}
