package com.blaze.assistant.ui.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.composer.ChatOverlayLogic
import com.blaze.assistant.composer.SpotlightSearch

/**
 * 0.47 chat composer + Spotlight v2: the shared state of the chat bar, the chat over Home and Spotlight.
 * One per Home; create it with [rememberComposerState] (or let [BlazeComposerHome] make one).
 */
@Stable
class ComposerState {
    /** The "Message Blaze" pill's text. */
    var text by mutableStateOf("")
    val attachments = mutableStateListOf<Attachment>()
    var attaching by mutableStateOf(false)
    var chatOpen by mutableStateOf(false)
    var spotlightOpen by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    /** The Spotlight field. */
    var query by mutableStateOf("")
    /** The distro picked on the right of the field for "!" commands. */
    var distro by mutableStateOf("alpine")
    /** An inline preview shown inside Spotlight (stays on Home), or null. */
    var preview by mutableStateOf<SpotlightSearch.Act.Preview?>(null)
    /** The newest "!" command run inline and its output. */
    var termCmd by mutableStateOf("")
    var termOut by mutableStateOf("")
    var termBusy by mutableStateOf(false)
    /** Messages after this id show in the chat over Home. */
    var sinceId by mutableLongStateOf(0L)
    /** User messages after this id were sent from the bar in this session: they lift out of the pill; replies after it type in. */
    var liveFrom by mutableLongStateOf(Long.MAX_VALUE)

    /** Blurred Home: chat or Spotlight is up. */
    val covering: Boolean get() = chatOpen || spotlightOpen
    /** The model chip shows once there's a typed character or an attachment. */
    val chipShown: Boolean get() = text.isNotEmpty() || attachments.isNotEmpty()
    val canSend: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()

    fun openSpotlight() { pickerOpen = false; spotlightOpen = true; preview = null }
    fun closeSpotlight() { spotlightOpen = false; preview = null; query = "" }

    fun openChat() {
        if (!chatOpen) sinceId = runCatching { ChatOverlayLogic.since(Agent.messages.value) }.getOrDefault(0L)
        spotlightOpen = false; preview = null; chatOpen = true
    }

    /** × on the chat: unblurs Home. */
    fun closeChat() { chatOpen = false; pickerOpen = false }

    /** Sends [message] (or the pill's text) with the attachments into the main chat and shows it over Home. */
    fun send(message: String = text) {
        val t = message.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        val files = attachments.toList()
        openChat()
        val newest = runCatching { Agent.messages.value.maxOfOrNull { it.id } ?: 0L }.getOrDefault(0L)
        if (liveFrom == Long.MAX_VALUE || liveFrom < newest) liveFrom = newest
        text = ""; attachments.clear(); pickerOpen = false
        Agent.send(t.ifEmpty { com.blaze.assistant.ui.AttachView.NO_TEXT }, attachments = files)
    }
}

@Composable
fun rememberComposerState(): ComposerState = remember { ComposerState() }
