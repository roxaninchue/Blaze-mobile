package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.46.3 (Rocky 18:20): the on-phone brain is never branded to users. No "OpenCode"/"opencode" in any user-facing
 * string: the UI and notification sources and string resources ([UiStrings]), the agent files that write setup status,
 * Settings › Brains labels, the mobile-data question and errors, and the changelog. THIRD_PARTY_NOTICES keeps its credit
 * (not scanned). The only setup prompt is the neutral mobile-data question with the size.
 */
class NoOpenCodeNameTest {
    private val name = Regex("(?i)\\bopen-?code\\b")

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun repoFile(rel: String): File = listOf(File(rel), File("../$rel")).first { it.exists() }

    /** Agent files whose strings reach the user (status lines, labels, prompts, errors, chat lines). */
    private val agentFiles = listOf("BrainOrder.kt", "BackupStatus.kt", "BackupNudge.kt", "SelfControl.kt", "PlainError.kt", "Friendly.kt", "Ack.kt")

    @Test fun userStringsNeverNameOpenCode() {
        val hits = UiStrings.literals().filter { (_, s) -> name.containsMatchIn(s) }.map { "${it.first}: ${it.second}" } +
            agentFiles.map { File(main(), "java/com/blaze/assistant/agent/$it") }.filter { it.isFile }
                .flatMap { f -> UiStrings.kotlinStrings(f.readText()).filter { name.containsMatchIn(it) }.map { "${f.name}: $it" } }
        assertEquals("OpenCode named in user-facing strings:\n" + hits.joinToString("\n"), 0, hits.size)
    }

    @Test fun backupBrainUserLinesAreNeutral() {
        val src = File(main(), "java/com/blaze/assistant/agent/BackupBrain.kt").readText()
        // the strings it returns to Settings / chat ("return@…") and its notes never name the server
        val lines = Regex("""return@\w+\s+"([^"]*)"""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue(lines.isNotEmpty())
        assertEquals(lines.filter { name.containsMatchIn(it) }.joinToString("\n"), 0, lines.count { name.containsMatchIn(it) })
    }

    @Test fun changelogDoesNotNameItButNoticesCreditIt() {
        val log = repoFile("CHANGELOG.md").readText()
        assertTrue("changelog names OpenCode", !name.containsMatchIn(log))
        assertTrue("notices keep the OpenCode credit", name.containsMatchIn(repoFile("THIRD_PARTY_NOTICES.md").readText()))
    }

    @Test fun statusAndPromptWording() {
        assertEquals("On-phone brain", BrainOrder.label(BackupRoute.Step.BACKUP))
        assertEquals("On-phone brain ready.", BackupStatus.line(BackupStatus.State.INSTALLED, "1.0", 78))
        assertEquals("Getting your on-phone brain ready (one-time)…", BackupStatus.line(BackupStatus.State.DOWNLOADING, "1.0", 78))
        assertEquals("Finish setting up Blaze on mobile data? About 80 MB, one time.", BackupNudge.mobileDataQuestion(BackupBrain.DOWNLOAD_BYTES))
    }
}
