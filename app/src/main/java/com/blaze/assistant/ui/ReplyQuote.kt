package com.blaze.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.RapidMessages
import com.blaze.assistant.agent.Role

/**
 * v0.30.6 rapid messages (Blazeinchue: "blaze highlights the one it's responding to and such"). Kept out of ChatScreen.kt so the
 * bubble styling there can change freely: a quote chip above a reply that answers a message that isn't right above it
 * (or several). Which chips show is decided in RapidMessages.chipMap (pure, RapidMessagesTest).
 *
 * 0.45.29 (user screenshot, 09:05): the soft wash behind a user message while Blaze worked on it (and after a chip jump)
 * read as a full-width grey box around the bubble and its Read label. It is gone: a user message is only its bubble.
 */
/** What the list needs to draw quotes: chips per reply id and the chat's messages by id. */
class ReplyRefs(val chips: Map<Long, List<Long>>, val byId: Map<Long, ChatMessage>)

/** Computed once per message list (one pass), not per bubble. */
@Composable
fun rememberReplyRefs(messages: List<ChatMessage>): ReplyRefs {
    val chips = remember(messages) { RapidMessages.chipMap(messages) }
    val byId = remember(chips, messages) {
        if (chips.isEmpty()) emptyMap() else {
            val want = chips.values.flatten().toHashSet()
            messages.filter { it.id in want }.associateBy { it.id }
        }
    }
    return remember(chips, byId) { ReplyRefs(chips, byId) }
}

/** Wraps one message of the thread: a Blaze reply that quotes gets its chips above it. Anything else is drawn untouched. */
@Composable
fun ReplyThread(m: ChatMessage, refs: ReplyRefs, onJump: (Long) -> Unit, content: @Composable () -> Unit) {
    when {
        m.role == Role.BLAZE && !refs.chips[m.id].isNullOrEmpty() -> Column(Modifier.fillMaxWidth()) {
            refs.chips[m.id].orEmpty().forEach { qid ->
                refs.byId[qid]?.let { q -> QuoteChip(RapidMessages.snippet(q.text)) { onJump(qid) } }
            }
            content()
        }
        else -> content()
    }
}

/** A texting-app reply quote: a thin accent bar, then one line of the quoted message. Tap = jump to it. */
@Composable
fun QuoteChip(text: String, onClick: () -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.padding(start = 2.dp, bottom = 4.dp).widthIn(max = 300.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.08f)).clickable(onClick = onClick)
        .semantics { contentDescription = "Replying to: $text. Tap to show it." }) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 16.sp, color = c.sub))
    }
}
