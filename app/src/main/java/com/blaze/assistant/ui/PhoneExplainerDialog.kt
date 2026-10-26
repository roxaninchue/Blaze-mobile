package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.phone.flash.PhoneExplainer

/**
 * 0.46.3: the one-time explainer for phone control (accessibility only, no ADB): what it does, what it never does, and one
 * button straight to Blaze's own switch in Android Settings. Shown once (PhoneExplainer.markShown on first composition).
 */
@Composable
fun PhoneExplainerDialog(onOpenSettings: () -> Unit, onClose: () -> Unit) {
    val c = LocalBlaze.current
    androidx.compose.runtime.LaunchedEffect(Unit) { PhoneExplainer.markShown { k, v -> com.blaze.assistant.agent.Settings.setBool(k, v) } }
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { onOpenSettings(); onClose() }) { Text(PhoneExplainer.OPEN) } },
        dismissButton = { TextButton(onClick = onClose) { Text(PhoneExplainer.LATER) } },
        title = { Text(PhoneExplainer.TITLE) },
        text = {
            Column {
                PhoneExplainer.POINTS.forEach { p ->
                    Text("\u2022 $p", modifier = Modifier.padding(bottom = 6.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, lineHeight = 19.sp, color = c.ink))
                }
                Spacer(Modifier.height(4.dp))
                Text(PhoneExplainer.STEPS, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.body))
            }
        })
}
