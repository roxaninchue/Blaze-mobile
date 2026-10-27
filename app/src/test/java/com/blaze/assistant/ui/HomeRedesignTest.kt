package com.blaze.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.47 Home redesign (Compose): the settled grid shows To-do only with tasks, and Interests from the news widgets. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi", application = android.app.Application::class)
class HomeRedesignTest {
    @get:Rule val rule = createComposeRule()

    @Test fun todoHiddenWithoutTasks() {
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(withTasks = false), playIntro = false, drawSky = false) }
        rule.onNodeWithText("To-do").assertDoesNotExist()
        rule.onNodeWithText("Track name").assertIsDisplayed()
    }

    @Test fun todoShownWithTasks() {
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(withTasks = true), playIntro = false, drawSky = false) }
        rule.onNodeWithText("To-do").assertIsDisplayed()
        rule.onNodeWithText("Gym at 6 PM").assertIsDisplayed()
    }

    @Test fun interestsFromNewsWidgets() {
        var opened = ""
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = false, onTopic = { opened = it.url }) }
        for (t in listOf("Programming", "Startups", "PlayStation")) rule.onNodeWithText(t).assertIsDisplayed()
        rule.onNodeWithText("Researchers have discovered a faster way to compile").assertIsDisplayed().performClick()
        assertEquals("https://example.com/a", opened)
        rule.onNodeWithText("IGN \u00b7 5 h ago").assertIsDisplayed()
    }

    @Test fun quickActionsAskBlaze() {
        var asked = ""
        rule.setContent { HomeRedesignContent(HomeRedesignSample.state(), playIntro = false, drawSky = false, onAction = { asked = it.prompt }) }
        rule.onNodeWithText("on Programming, Startups and PlayStation news").assertIsDisplayed().performClick()
        assertEquals("Catch me up on the latest Programming, Startups and PlayStation news.", asked)
    }
}
