package com.blaze.assistant.agent

import com.blaze.assistant.agent.SecretTestKit.CF
import com.blaze.assistant.agent.SecretTestKit.GL
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.46.6 Secure form, end to end on the pure pieces: request_secret -> card -> vault -> handle in a tool call -> value
 * swapped in -> output scrubbed -> history / chat / log / wire. Every assertion is on serialized state: no secret
 * value may appear in the model history, the chat lines, a log line or a request body.
 */
class SecretLeakTest {
    @Before fun setUp() { SecretTestKit.fresh(); SecretDesk.waitMs = 5_000 }

    private suspend fun answerWhenShown(values: Map<String, String>?) {
        repeat(200) { if (SecretDesk.pending.value != null) { SecretDesk.answer(values); return }; delay(5) }
        throw AssertionError("no Secure Form card")
    }

    @Test fun requestSecretGivesHandleOnly() = runBlocking {
        val steps = ArrayList<String>()
        val args = JSONObject().put("kind", "token").put("label", "Cloudflare API token").put("service", "cloudflare").put("hint", "Workers + Pages edit")
        val r = async { SecretDesk.run(args, { steps += it }) }
        answerWhenShown(mapOf("value" to CF))
        val out = r.await()
        assertTrue(out, out.contains("{{secret:cloudflare_token}}")); assertTrue(out.contains("…" + CF.takeLast(4)))
        assertFalse(out.contains(CF)); assertFalse(steps.joinToString().contains(CF))
        assertEquals("Saved securely", SecretDesk.saved.value?.let { "Saved securely" })
        // The second ask for the same service reuses it without a card.
        val again = SecretDesk.run(args)
        assertTrue(again.startsWith("Already saved securely"))
    }

    @Test fun cancelSaysSoAndSavesNothing() = runBlocking {
        val r = async { SecretDesk.run(JSONObject().put("kind", "password").put("label", "Router password").put("service", "router.local")) }
        answerWhenShown(null)
        assertTrue(r.await().contains("cancelled")); assertTrue(SecretVault.list().isEmpty())
    }

    @Test fun cardRequestHasThreeFieldsAndNeedsValidCard() {
        val req = SecretDesk.requestOf(JSONObject().put("kind", "card").put("label", "Card for the shop").put("service", "shop.example.com"))
        assertEquals(listOf("number", "exp", "cvc"), req.fields.map { it.name })
        assertEquals(listOf("shop.example.com"), req.hosts)
        assertFalse(com.blaze.assistant.ui.SecretFormRules.ready(req.kind, req.fields, mapOf("number" to "4242424242424241", "exp" to "12/29", "cvc" to "123")))
        assertTrue(com.blaze.assistant.ui.SecretFormRules.ready(req.kind, req.fields, mapOf("number" to "4242 4242 4242 4242", "exp" to "12/29", "cvc" to "123")))
        val login = SecretDesk.requestOf(JSONObject().put("kind", "login").put("label", "GitLab").put("service", "gitlab"))
        assertTrue(com.blaze.assistant.ui.SecretFormRules.ready(login.kind, login.fields, mapOf("password" to "x")))
    }

    @Test fun noSecretInHistoryChatLogsOrWire() {
        SecretTestKit.cloudflare(); SecretTestKit.gitlab()
        val history = JSONArray()
        val chat = ArrayList<String>()
        val log = ArrayList<String>()
        // 1. The model's call carries handles.
        val call = JSONObject().put("url", "https://api.cloudflare.com/client/v4/user/tokens/verify")
            .put("headers", JSONObject().put("Authorization", "Bearer {{secret:cloudflare_token}}"))
        history.put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "c1")
            .put("function", JSONObject().put("name", "http_request").put("arguments", call.toString())))))
        // 2. On the phone the value goes in (what the tool really runs with).
        val prep = SecretUse.prepare("http_request", call) as SecretUse.Prep.Ok
        assertTrue(prep.args.toString().contains(CF))
        // 3. The tool echoes the header (a debug API does this); the step line shows the command; a crash logs it.
        val toolOut = "200 OK\n{\"echo\":{\"Authorization\":\"Bearer $CF\"},\"also\":\"$GL\"}"
        val result = SecretVault.scrub(toolOut)
        history.put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("name", "http_request").put("content", result))
        chat += SecretVault.scrub("$ curl -H 'Authorization: Bearer $CF' https://api.cloudflare.com")
        log += SecretVault.scrub("ERROR tool http_request: IOException: bad header Bearer $CF")
        // 4. The request body that goes to the model / gateway.
        val wire = SecretVault.scrub(JSONObject().put("messages", history).toString())
        for ((what, s) in listOf("history" to history.toString(), "chat" to chat.joinToString(), "log" to log.joinToString(), "wire" to wire)) {
            assertFalse("$what leaks the Cloudflare token", s.contains(CF))
            assertFalse("$what leaks the GitLab token", s.contains(GL))
        }
        assertTrue(history.toString().contains("{{secret:cloudflare_token}}"))
        assertFalse(SecretVault.leaks(wire))
    }

    @Test fun nextLaunchCrashReportIsScrubbed() {
        SecretTestKit.cloudflare(); SecretTestKit.gitlab()
        val C = com.blaze.assistant.device.CrashLog
        // 0.46.5's last-exit record (native trace + in-flight marker) and the report that leaves the phone.
        val line = C.exitLine(4, "crash with Bearer $CF", "backtrace: libquickjs.so $GL", "provider call with token $CF", listOf("My Source"))!!
        assertFalse(line.contains(CF)); assertFalse(line.contains(GL))
        assertTrue(line.contains("{{secret:cloudflare_token}}")); assertTrue(line.contains("{{secret:gitlab_token}}"))
        val report = C.reportText("2026-10-26 21:00:00  QUIET old line written before the save: $GL\n")
        assertFalse(report.contains(GL)); assertTrue(report.contains("{{secret:gitlab_token}}"))
        val src = java.io.File("src/main/java/com/blaze/assistant/device/CrashLog.kt").let { if (it.exists()) it else java.io.File("app/" + it.path) }.readText()
        assertTrue(src.contains("SecretVault.file = File(ctx.filesDir, \"secret_vault.json\")"))   // known before the crash handler / last-exit check
        assertTrue(src.contains("redact(scrubSecrets(label), secrets)"))                            // in-flight marker
        assertTrue(src.contains("reportText(file.readText())"))                                      // read() / Send crash report / crash_log tool
    }

    @Test fun pasteIsHeldThenSavedAndRedacted() {
        val sent = ArrayList<String>()
        val msg = "here's my GitLab token $GL can you push the branch?"
        assertTrue(SecretDesk.hold(msg, false) { sent += it })
        val h = SecretDesk.paste.value; assertNotNull(h)
        assertFalse("the hold card never shows it", h!!.masked.contains(GL))
        val saved = SecretDesk.saveHeld()
        assertEquals(1, saved.size)
        assertEquals("here's my GitLab token {{secret:gitlab_token}} can you push the branch?", sent.single())
        assertFalse(sent.single().contains(GL))
        // The redacted message passes straight through.
        assertFalse(SecretDesk.hold(sent.single(), false) { sent += it })
        assertEquals(GL, SecretVault.value("gitlab_token"))
        assertEquals("here's my GitLab token \uD83D\uDD12 GitLab token can you push the branch?", SecretVault.displayHandles(sent.first()))
    }

    @Test fun pasteSendAnywayGoesOnceAsTyped() {
        val sent = ArrayList<String>()
        val msg = SecretTestKit.SK
        assertTrue(SecretDesk.hold(msg, false) { sent += it })
        SecretDesk.sendHeldAnyway()
        assertEquals(listOf(msg), sent)
        assertTrue(SecretVault.list().isEmpty())
    }

    @Test fun onlySecretMessageBecomesSavedLine() {
        val sent = ArrayList<String>()
        assertTrue(SecretDesk.hold(CF + " (cloudflare)", false) { sent += it })
        SecretDesk.saveHeld()
        assertFalse(sent.single().contains(CF)); assertTrue(sent.single().contains("{{secret:cloudflare_token}}"))
    }
}
