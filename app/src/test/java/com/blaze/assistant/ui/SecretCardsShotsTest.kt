package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.SecretDesk
import com.blaze.assistant.agent.SecretVault
import com.blaze.assistant.agent.Settings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 0.46.6 screenshots (shots job, BLAZE_SHOTS=1): the Secure Form card, the paste hold and "Saved securely", light and dark (glass). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class SecretCardsShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() { try { Settings.init(ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "SecretCardsShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    private fun frame(colors: BlazeColors, name: String) {
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides colors) {
                Column(Modifier.fillMaxSize().background(colors.paper).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecretForm(SecretVault.Kind.API_KEY, "Cloudflare API token", "cloudflare", "Workers and Pages edit, nothing account-wide.",
                        SecretVault.fieldsFor(SecretVault.Kind.API_KEY, "Cloudflare API token"), onSave = {}, onCancel = {})
                    SecretForm(SecretVault.Kind.CARD, "Card for the shop", "shop.example.com", "", SecretVault.fieldsFor(SecretVault.Kind.CARD), onSave = {}, onCancel = {})
                    SecretPasteHold(listOf("GitLab token"), "here's my token •••••••• (GitLab token)", onSave = {}, onSend = {})
                    SavedSecurelyLine("GitLab token", "…3Mn9")
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNode(hasContentDescription("Secure form: Cloudflare API token")).assertExists()
        rule.onAllNodes(hasText(SecretDesk.COPY)).fetchSemanticsNodes().let { assertTrue("lock line missing", it.isNotEmpty()) }
        assertTrue("$name frame is blank", distinct(capture(name)) > 12)
    }

    @Test fun secureFormDark() = frame(DarkColors, "secure-form-dark")
    @Test fun secureFormLight() = frame(LightColors, "secure-form-light")

    @Test fun pasteHoldAndSavedDark() {
        rule.setContent {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                Column(Modifier.fillMaxSize().background(DarkColors.paper).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecretPasteHold(listOf("GitLab token"), "here's my token •••••••• (GitLab token) can you push?", onSave = {}, onSend = {})
                    SavedSecurelyLine("GitLab token", "…3Mn9")
                    SecretForm(SecretVault.Kind.LOGIN, "GitLab login", "gitlab", "", SecretVault.fieldsFor(SecretVault.Kind.LOGIN), onSave = {}, onCancel = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onAllNodes(hasText("Send anyway")).fetchSemanticsNodes().let { assertTrue("Send anyway missing", it.isNotEmpty()) }
        assertTrue(distinct(capture("paste-hold-saved-dark")) > 12)
    }
}
