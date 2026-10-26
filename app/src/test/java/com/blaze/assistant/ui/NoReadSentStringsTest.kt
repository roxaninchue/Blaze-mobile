package com.blaze.assistant.ui

import com.blaze.assistant.agent.CloudRuns
import com.blaze.assistant.agent.Delivery
import com.blaze.assistant.agent.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.47.0 (Rocky, 26 Oct): no "Blaze is thinking", "Sent. Waiting for Blaze…", "Sent" or "Read" chrome anywhere in the chat;
 * the bubble shows a spinner then a tick, the work shows one glass pill. No "Hark" and no Halo picker in user strings.
 */
class NoReadSentStringsTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/blaze/assistant/$rel"), File("app/src/main/java/com/blaze/assistant/$rel")).first { it.exists() }.readText()
    private fun code(rel: String): String = src(rel).lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun deliveryHasNoWords() {
        assertEquals("", Delivery.label(DeliveryState.SENDING))
        assertEquals("", Delivery.label(DeliveryState.SENT))
        assertEquals("", Delivery.label(DeliveryState.READ))
        DeliveryState.values().forEach { assertFalse(Regex("\\bRead\\b|\\bSent\\b").containsMatchIn(Delivery.label(it) + Delivery.spoken(it))) }
    }

    @Test fun noThinkingOrWaitingChrome() {
        listOf(CloudRuns.SENDING, CloudRuns.QUEUED, CloudRuns.RUNNING).forEach {
            assertFalse(it, it.contains("thinking", true)); assertFalse(it, it.contains("Waiting for Blaze")); assertFalse(it, it.startsWith("Sent"))
        }
        for (f in listOf("ui/ChatScreen.kt", "ui/DeliveryStatus.kt", "ui/WorkPill.kt", "agent/CloudRuns.kt", "agent/Delivery.kt")) {
            val c = code(f)
            assertFalse("$f: Blaze is thinking", c.contains("Blaze is thinking"))
            assertFalse("$f: Sent. Waiting", c.contains("Sent. Waiting"))
            assertFalse("$f: \"Read\" label", c.contains("\"Read\""))
            assertFalse("$f: \"Sent\" label", c.contains("\"Sent\""))
        }
    }

    @Test fun noHarkAndNoPicker() {
        for (f in listOf("ui/WorkPill.kt", "ui/AuditLogPage.kt", "agent/orch/LightLane.kt", "agent/orch/Butler.kt", "agent/orch/OrchVoice.kt")) {
            assertFalse(f, Regex("(?i)\"[^\"]*hark[^\"]*\"").containsMatchIn(code(f)))
        }
        val chat = code("ui/ChatScreen.kt")
        assertTrue("the arc picker never opens", chat.contains("AnimatedVisibility(false && arcOpen"))
        assertTrue("the header orb shows plain Blaze", chat.contains("talkTo = \"\","))
    }
}
