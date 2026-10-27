package com.blaze.assistant.composer

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** 0.47 chat over Home: what shows, typing dots, type-in speed, the centred timestamp, and the composer's source rules. */
class ChatOverlayLogicTest {
    private fun m(id: Long, role: Role, text: String = "t$id", at: Long = 0L) = ChatMessage(id, role, text, at = at)

    @Test fun sinceKeepsALittleContext() {
        val all = (1L..10L).map { m(it, if (it % 2L == 0L) Role.BLAZE else Role.USER) }
        assertEquals(6L, ChatOverlayLogic.since(all))
        assertEquals(4, ChatOverlayLogic.visible(all, ChatOverlayLogic.since(all)).size)
        assertEquals(0L, ChatOverlayLogic.since(all.take(3)))
    }

    @Test fun visibleSkipsStepsAndBlanks() {
        val all = listOf(m(1, Role.USER), m(2, Role.STEP, "Looking it up"), m(3, Role.BLAZE, ""), m(4, Role.BLAZE), m(5, Role.ERROR, "Couldn't reach Blaze"))
        assertEquals(listOf(1L, 4L, 5L), ChatOverlayLogic.visible(all, 0L).map { it.id })
        assertEquals(listOf(4L, 5L), ChatOverlayLogic.visible(all, 3L).map { it.id })
    }

    @Test fun typingDotsOnlyWhileWaitingForTheAnswer() {
        assertTrue(ChatOverlayLogic.typing(listOf(m(1, Role.USER)), busy = true))
        assertFalse(ChatOverlayLogic.typing(listOf(m(1, Role.USER)), busy = false))
        assertFalse(ChatOverlayLogic.typing(listOf(m(1, Role.USER), m(2, Role.BLAZE)), busy = true))
    }

    @Test fun typeInIsQuickButVisible() {
        assertEquals(2, ChatOverlayLogic.step(10))
        assertTrue(ChatOverlayLogic.step(9_000) >= 100)   // a long reply still finishes in ~1.5 s
    }

    @Test fun stamp() {
        val z = TimeZone.getTimeZone("Africa/Lusaka")
        val now = 1_793_000_000_000L
        assertTrue(ChatOverlayLogic.stamp(now - 60_000, now, z).startsWith("Today "))
        assertTrue(ChatOverlayLogic.stamp(now - 86_400_000, now, z).startsWith("Yesterday "))
        assertEquals("", ChatOverlayLogic.stamp(0L, now, z))
    }

    @Test fun initials() {
        assertEquals("R", ChatOverlayLogic.initial("roxan"))
        assertEquals("Y", ChatOverlayLogic.initial(""))
        assertEquals("Y", ChatOverlayLogic.initial(null))
    }

    private fun src(rel: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return File(root, "com/blaze/assistant/$rel").readText()
    }

    @Test fun composerSourcesFollowTheRules() {
        val files = listOf("ui/composer/ChatComposer.kt", "ui/composer/SpotlightPanel.kt", "ui/composer/ComposerHost.kt", "ui/composer/ComposerState.kt",
            "ui/composer/CachedBlur.kt", "composer/SelfMap.kt", "composer/SpotlightSearch.kt", "composer/ModelChoice.kt", "composer/SpotlightLook.kt", "composer/SoftBlur.kt")
        val all = files.joinToString("\n") { src(it) }
        // no other branding, never the forbidden model names, never "No results"
        assertFalse(Regex("(?i)\\b(hark|jarvis|nvidia|nemotron|system one|jev)\\b").containsMatchIn(all.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")))
        assertFalse(all.contains("\"No results"))
        // the chat bar: Message Blaze pill, send arrow inside it, + opens Spotlight
        val bar = src("ui/composer/ChatComposer.kt")
        assertTrue(bar.contains("\"Message Blaze\"") && bar.contains("Icons.Rounded.ArrowUpward") && bar.contains("state.openSpotlight()"))
        // RC2: the Blaze orb sits in the pill and keeps the old button's job (tap: chat; hold: talk)
        assertTrue(bar.contains("BlazeOrbButton(state)") && bar.contains("onLongClick = onHold") && bar.contains("listenRequests"))
        // RC2: ONE cached software blur on every phone (low-RAM too): captured once, blurred off main, freed on close
        val host = src("ui/composer/ComposerHost.kt")
        val blur = src("ui/composer/CachedBlur.kt")
        assertTrue(host.contains("rememberCachedBlur()") && host.contains("CachedBlurEffect(blur, state.covering)") && host.contains("cachedBlurSource(blur)"))
        assertTrue(blur.contains("withContext(Dispatchers.Default)") && blur.contains("SoftBlur.blur(") && blur.contains("fun release()"))
        // RC2: Spotlight floats at the TOP (never a bottom sheet)
        assertTrue(host.contains("contentAlignment = Alignment.TopCenter") && !host.contains("contentAlignment = Alignment.BottomCenter"))
        // reduce motion respected
        assertTrue(bar.contains("MotionTokens.enabled") && host.contains("MotionTokens.enabled") && blur.contains("MotionTokens.enabled"))
        // search never runs on main
        assertTrue(src("ui/composer/SpotlightPanel.kt").contains("withContext(Dispatchers.Default)"))
        // routing only reads the choice
        assertTrue(src("agent/LlmClient.kt").contains("ModelChoice.wire()?.let { put(\"blaze_family\", it) }"))
    }
}
