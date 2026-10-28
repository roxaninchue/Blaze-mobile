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
    /** RC4: focus for the "Message Blaze" field (the host / checks can raise the keyboard on it). */
    val barFocus = androidx.compose.ui.focus.FocusRequester()
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

    /**
     * 0.48.0 RC4: the host's send path (slash commands, /clear, call words, project chats, quotes) so a message from the
     * bar goes exactly where one from the old chat screen went. Null = straight to [Agent.send].
     */
    var sender: ((String, List<Attachment>) -> Unit)? = null
    /** RC4: composer / Spotlight actions only the host can run (camera, photos, call, plan / approval modes…). True = handled. */
    var hostAction: ((String) -> Boolean)? = null
    /** RC4: the chat over Home's title ("" = the main chat): a project chat, a private chat. */
    var title by mutableStateOf("")
    /** RC4: a private chat is shown (× asks before leaving). */
    var privateChat by mutableStateOf(false)
    /** RC4: × on a private chat (the host asks first); null = just close. */
    var onClosePrivate: (() -> Unit)? = null

    /** Blurred Home: chat or Spotlight is up. */
    val covering: Boolean get() = chatOpen || spotlightOpen
    /** The model chip shows once there's a typed character or an attachment. */
    val chipShown: Boolean get() = text.isNotEmpty() || attachments.isNotEmpty()
    val canSend: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()

    fun openSpotlight() { pickerOpen = false; spotlightOpen = true; preview = null }
    fun closeSpotlight() { spotlightOpen = false; preview = null; query = "" }

    /** Opens the chat over Home: the last few messages ([full]: the whole thread, e.g. history, a project chat). */
    fun openChat(full: Boolean = false) {
        if (!chatOpen) sinceId = if (full) 0L else runCatching { ChatOverlayLogic.since(Agent.messages.value) }.getOrDefault(0L)
        else if (full) sinceId = 0L
        spotlightOpen = false; preview = null; query = ""; chatOpen = true
    }

    /** RC4: "Show earlier messages" at the top of the chat over Home (the whole thread is history now). */
    fun showEarlier() { sinceId = 0L }

    /** × on the chat: unblurs Home (a private chat asks first). */
    fun closeChat() {
        if (privateChat && onClosePrivate != null) { onClosePrivate?.invoke(); return }
        chatOpen = false; pickerOpen = false
    }

    /** Closes without asking (the host already did, or the chat it showed is gone). */
    fun dismissChat() { chatOpen = false; pickerOpen = false }

    /** Sends [message] (or the pill's text) with the attachments into the main chat and shows it over Home. */
    fun send(message: String = text) {
        val t = message.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        val files = attachments.toList()
        openChat()
        val newest = runCatching { Agent.messages.value.maxOfOrNull { it.id } ?: 0L }.getOrDefault(0L)
        if (liveFrom == Long.MAX_VALUE || liveFrom < newest) liveFrom = newest
        text = ""; attachments.clear(); pickerOpen = false
        val body = t.ifEmpty { com.blaze.assistant.ui.AttachView.NO_TEXT }
        sender?.invoke(body, files) ?: Agent.send(body, attachments = files)
    }
}

@Composable
fun rememberComposerState(): ComposerState = remember { ComposerState() }
