package com.blaze.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.blaze.assistant.device.StartLater

/**
 * Lazy start, the generic hook for heavy first-screen UI: [content] is composed from the frame after Blaze's first frame
 * (StartLater.firstFrame); the first frame shows [placeholder] (nothing by default). Use it for cards that are costly to
 * compose and not needed to show Blaze is open (the Home board's cards today; the redesigned Home can wrap its own).
 */
@Composable
fun AfterFirstFrame(placeholder: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    if (firstFrameShown()) content() else placeholder()
}

/** True from the frame after Blaze's first frame (always true after that, also on later screens). */
@Composable
fun firstFrameShown(): Boolean {
    val shown by StartLater.firstFrame.collectAsState()
    return shown
}
