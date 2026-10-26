package com.blaze.assistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.ReplySources

/**
 * 0.46.2 immersive replies: the pages a turn searched / read as one horizontally scrolling row of small source pills (favicon +
 * short title or domain; tap = the chat's glass window preview, long-press = copy the link), and up to three pictures those pages
 * carried as a small rounded strip above the text (lazy, cached by HomeImages; a picture that fails or is icon-sized is simply not
 * shown; low-memory phones show one, small). Tap a picture = full screen, with the source domain credited.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcePills(found: ReplySources.Found, modifier: Modifier = Modifier) {
    if (found.sources.isEmpty()) return
    val c = LocalBlaze.current
    val open = LocalOpenLink.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    Row(modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp, top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        found.sources.forEach { s ->
            Row(Modifier.clip(RoundedCornerShape(50)).background(c.faint).border(1.dp, c.hair, RoundedCornerShape(50))
                .combinedClickable(role = Role.Button, onClickLabel = "Open ${s.domain}",
                    onLongClick = { clip.setText(AnnotatedString(s.url)); android.widget.Toast.makeText(ctx, "Link copied", android.widget.Toast.LENGTH_SHORT).show() },
                    onClick = { open?.invoke(s.url) ?: runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s.url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                .semantics { contentDescription = "Source: ${s.label}" }
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Favicon(s.domain)
                Text(s.label, color = c.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp).widthIn(max = 150.dp))
            }
        }
    }
}

/** The site's own icon (https://<domain>/favicon.ico); while it loads, or when there's none, the domain's initial. */
@Composable
private fun Favicon(domain: String) {
    val c = LocalBlaze.current
    val img = if (domain.isBlank()) null else rememberHomeImage("https://$domain/favicon.ico")
    Box(Modifier.size(18.dp).clip(CircleShape).background(c.hair), contentAlignment = Alignment.Center) {
        if (img != null && img.width >= 8) Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(domain.firstOrNull()?.uppercase() ?: "", color = c.sub, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Up to three pictures from the turn's pages (one on low-memory phones), above the reply's text. Nothing shows until one loads. */
@Composable
fun ReplyImages(found: ReplySources.Found, low: Boolean, modifier: Modifier = Modifier) {
    val list = found.images.take(if (low) 1 else ReplySources.MAX_IMAGES)
    if (list.isEmpty()) return
    var full by remember { mutableStateOf<ReplySources.Image?>(null) }
    Row(modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { im ->
            val bmp = rememberHomeImage(im.url)
            if (bmp != null && ReplySources.bigEnough(bmp.width, bmp.height)) {
                val single = list.size == 1
                Column(Modifier.widthIn(max = if (single) 300.dp else 180.dp)) {
                    Image(bmp, contentDescription = "Picture from ${im.domain}", contentScale = ContentScale.Crop,
                        modifier = Modifier.height(if (single) 170.dp else 110.dp).widthIn(min = 120.dp, max = if (single) 300.dp else 180.dp)
                            .clip(RoundedCornerShape(14.dp)).clickable(role = Role.Image, onClickLabel = "Open the picture") { full = im })
                    Text(im.domain, color = LocalBlaze.current.sub, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }
        }
    }
    full?.let { im ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { full = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = null }, contentAlignment = Alignment.Center) {
                rememberHomeImage(im.url)?.let { Image(it, contentDescription = "Picture from ${im.domain}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp)) }
                Text("From ${im.domain}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
            }
        }
    }
}
