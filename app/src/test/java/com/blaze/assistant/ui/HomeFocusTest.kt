package com.blaze.assistant.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.home.HomeGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** RC2 card focus: open from a card's header, dismiss by a tap outside and by Back, taps inside still work, and it survives recreation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi", application = android.app.Application::class)
class HomeFocusTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun state() = HomeRedesignSample.state().let { it.copy(allTopics = it.topics + HomeRedesignSample.extraTopics()) }

    @Test fun openFromHeaderShowsEveryInterest() {
        val f = HomeFocusState()
        rule.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false, focus = f) }
        rule.onNodeWithText("Interests \u00b7 Tracking").performClick()
        rule.waitForIdle()
        assertEquals(HomeGrid.Card.INTERESTS, f.target)
        assertEquals(1f, f.progress.value)
        rule.onNodeWithText("Edit interests").assertIsDisplayed()
        // the grid shows 3, the focused card all of them
        rule.onNodeWithText("Formula One").assertIsDisplayed()
        rule.onNodeWithText("Ferrari brings a new floor to Austin").assertIsDisplayed()
    }

    @Test fun tapOutsideClosesIntoTheCell() {
        val f = HomeFocusState()
        rule.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false, focus = f) }
        rule.runOnIdle { f.open(HomeGrid.Card.WEATHER) }
        rule.onNodeWithText("Open the forecast").assertIsDisplayed()
        rule.onRoot().performTouchInput { click(Offset(6f, 6f)) }   // the blurred side, outside the card
        rule.waitForIdle()
        assertFalse(f.isOpen); assertFalse(f.shown)
        assertEquals(0f, f.progress.value)
        rule.onNodeWithText("Open the forecast").assertDoesNotExist()
    }

    @Test fun backClosesIt() {
        val f = HomeFocusState()
        rule.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false, focus = f) }
        rule.runOnIdle { f.open(HomeGrid.Card.MUSIC) }
        rule.onNodeWithText("Open Music").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(f.shown)
        rule.onNodeWithText("Open Music").assertDoesNotExist()
    }

    @Test fun tapsInsideStillWorkAndKeepItOpen() {
        val f = HomeFocusState()
        var opened = ""; var asked = ""
        rule.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false, focus = f, onTopic = { opened = it.url }, onAction = { asked = it.verb }) }
        rule.runOnIdle { f.open(HomeGrid.Card.INTERESTS) }
        rule.onAllNodesWithText("Researchers have discovered a faster way to compile").onLast().performClick()
        assertEquals("https://example.com/a", opened)
        assertTrue(f.isOpen)
        // a Quick action asks Blaze and closes the card
        rule.runOnIdle { f.close() }; rule.waitForIdle()
        rule.runOnIdle { f.open(HomeGrid.Card.QUICK) }; rule.waitForIdle()
        rule.onAllNodesWithText("Plan").onLast().performClick()
        rule.waitForIdle()
        assertEquals("Plan", asked); assertFalse(f.shown)
    }

    @Test fun survivesRecreation() {
        val tester = StateRestorationTester(rule)
        tester.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false) }
        rule.onNodeWithText("Interests \u00b7 Tracking").performClick()
        rule.onNodeWithText("Edit interests").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Edit interests").assertIsDisplayed()
    }

    @Test fun reduceMotionCrossfades() {
        MotionTokens.setEnabledForTests(false)
        try {
            val f = HomeFocusState()
            rule.setContent { HomeRedesignContent(state(), playIntro = false, drawSky = false, focus = f) }
            rule.runOnIdle { f.open(HomeGrid.Card.TODO) }
            rule.onNodeWithText("Open the list").assertIsDisplayed()
            // in place: the grid card stays (no lift), the overlay only fades
            assertEquals(1f, f.gridAlpha(HomeGrid.Card.TODO))
        } finally { MotionTokens.setEnabledForTests(true) }
    }
}
