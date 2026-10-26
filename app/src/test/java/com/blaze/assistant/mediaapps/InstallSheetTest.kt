package com.blaze.assistant.mediaapps

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.movies.ProviderRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.46.3: with 25 providers on a small screen the Install button stays visible and works (the list scrolls, the bar doesn't). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h480dp-mdpi", application = android.app.Application::class)
class InstallSheetTest {
    @get:Rule val rule = createComposeRule()
    private val entries = (1..25).map { ProviderRepo.Entry("provider$it", "Provider $it", "1.0") }

    @Test fun installButtonVisibleAndClickableWith25Providers() {
        val picked = mutableStateListOf<String>()
        var installed = 0
        val installing = mutableStateOf(false)
        rule.setContent {
            InstallSheet(entries, emptySet(), picked, emptyMap(), installing.value,
                onToggle = { v -> if (!picked.remove(v)) picked += v },
                onAll = { val n = InstallPick.toggleAll(entries, picked); picked.clear(); picked.addAll(n) },
                onInstall = { installed++ }, onCancel = { })
        }
        rule.onNodeWithContentDescription("Install (0)").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Select all").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Install (25)").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, installed)
        rule.onNodeWithContentDescription("Select none").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Install (0)").assertIsDisplayed()
        rule.onNodeWithContentDescription("Install Provider 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Install (1)").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithContentDescription("Providers to install").assertIsDisplayed()
    }

    @Test fun pureRules() {
        assertEquals("Install (3)", InstallPick.label(3))
        assertFalse(InstallPick.canInstall(0, false)); assertFalse(InstallPick.canInstall(2, true)); assertTrue(InstallPick.canInstall(2, false))
        assertEquals(25, InstallPick.toggleAll(entries, emptyList()).size)
        assertEquals(0, InstallPick.toggleAll(entries, entries.map { it.value }).size)
        assertEquals(24, InstallPick.listed(entries.mapIndexed { i, e -> if (i == 0) e.copy(disabled = true) else e }).size)
    }
}
