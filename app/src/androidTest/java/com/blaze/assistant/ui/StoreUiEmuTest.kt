package com.blaze.assistant.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.appstore.AppStore
import com.blaze.assistant.appstore.EmuEvidence
import com.blaze.assistant.appstore.EmuEvidence.FAIL
import com.blaze.assistant.appstore.EmuEvidence.NOT_TESTED
import com.blaze.assistant.appstore.EmuEvidence.PASS
import com.blaze.assistant.appstore.StorePolicy
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * app/emu-store: EMULATOR check that the Store page itself starts and lists apps on this Android version. The page is
 * hosted directly (BlazeTheme + AppStorePage), not reached through MainActivity › Settings, and the Get button's
 * ConfirmGate card lives in the chat UI, so neither is covered here (recorded as not-tested).
 */
@RunWith(AndroidJUnit4::class)
class StoreUiEmuTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun storePageRendersAndLists() {
        val sc = "store-ui"
        var ok = true
        try {
            rule.setContent { BlazeTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { AppStorePage() } } }
            rule.waitUntil(20_000) { rule.onAllNodes(hasText(StorePolicy.FREE)).fetchSemanticsNodes().isNotEmpty() }
            EmuEvidence.record(sc, "store_page_renders", PASS, "AppStorePage composed; policy line shown")
        } catch (e: Throwable) {
            ok = false; EmuEvidence.record(sc, "store_page_renders", FAIL, "${e.javaClass.simpleName}: ${e.message}")
        }
        val n = AppStore.catalog.value.apps.size
        val rows = try {
            rule.waitUntil(15_000) { rule.onAllNodes(hasContentDescription(StorePolicy.NOTICE, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(hasContentDescription(StorePolicy.NOTICE, substring = true)).fetchSemanticsNodes().size
        } catch (e: Throwable) { 0 }
        EmuEvidence.record(sc, "catalog_listed", if (rows > 0) PASS else FAIL, "catalog apps=$n, rows on page=$rows")
        if (rows == 0) ok = false
        val allowShown = rule.onAllNodes(hasText("Allow installs")).fetchSemanticsNodes().isNotEmpty()
        EmuEvidence.record(sc, "permission_banner", if (allowShown == !AppStore.canInstall()) PASS else FAIL,
            "canInstall=${AppStore.canInstall()} 'Allow installs' shown=$allowShown")
        EmuEvidence.shot("store-ui-list")
        try {
            rule.onAllNodes(hasContentDescription(StorePolicy.NOTICE, substring = true))[0].performClick()
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("Get") or hasText("Open") or hasText("Update")).fetchSemanticsNodes().isNotEmpty() }
            EmuEvidence.record(sc, "get_button_shown", PASS, "first row expanded; Get/Open/Update shown")
            EmuEvidence.shot("store-ui-expanded")
        } catch (e: Throwable) {
            ok = false; EmuEvidence.record(sc, "get_button_shown", FAIL, "${e.javaClass.simpleName}: ${e.message}")
        }
        EmuEvidence.record(sc, "main_activity_navigation", NOT_TESTED, "MainActivity › Settings › App Store navigation not driven; page hosted directly")
        EmuEvidence.record(sc, "confirm_gate_card", NOT_TESTED, "the chat ConfirmGate card (Agent.confirm) is not rendered in this host; the pipeline tests answer it yes")
        assertTrue("store UI", ok)
    }
}
