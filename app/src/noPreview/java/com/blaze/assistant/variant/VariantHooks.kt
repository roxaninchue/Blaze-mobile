package com.blaze.assistant.variant

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Build-variant hooks called from main code. This is the plain version used by release, debug and kilnTest: every hook
 * does nothing, so those builds carry no hidden switch and no extra screen. A private test build ships its own copy
 * (src/<variant>/java) with the same signatures.
 */
object VariantHooks {
    /** Whether the version line in Settings > About listens for taps. */
    const val VERSION_TAPS = false

    @Suppress("UNUSED_PARAMETER")
    fun versionTapped(context: Context) = Unit

    @Composable
    fun AboutExtras() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun skipAppInit(app: Application): Boolean = false
}
