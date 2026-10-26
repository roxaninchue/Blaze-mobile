package com.blaze.assistant.agent.orch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 0.47.0 risk classifier, confirm gate, guards, undo, resume, acceptance and the audit log. */
class SafetyTest {
    private fun j(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { put(it.first, it.second) } }
    private fun tmp(): File = Files.createTempDirectory("orch").toFile()

    // ------------------------------------------------------------ Risk
    @Test fun readsRunFree() {
        listOf("web_search", "fetch_page", "read_screen", "file_read", "calendar_events", "recall_memory", "gmail_read").forEach {
            assertEquals(it, Risk.Level.READ, Risk.classify(it).level)
        }
    }

    @Test fun reversibleChanges() {
        listOf("file_write", "file_edit", "set_alarm", "create_routine", "device_setting", "compose_sms", "open_app", "remember").forEach {
            assertEquals(it, Risk.Level.REVERSIBLE, Risk.classify(it).level)
        }
    }

    @Test fun outwardAlwaysOutward() {
        mapOf("send_sms" to Risk.Kind.SEND, "gmail_send" to Risk.Kind.SEND, "call_contact" to Risk.Kind.CALL, "file_delete" to Risk.Kind.DELETE,
            "uninstall_app" to Risk.Kind.INSTALL, "slack_post" to Risk.Kind.POST, "run_ussd" to Risk.Kind.PAY, "password" to Risk.Kind.ACCOUNT,
            "share_to_app" to Risk.Kind.SHARE, "github_install_apk" to Risk.Kind.INSTALL).forEach { (t, k) ->
            val v = Risk.classify(t, j("to" to "Mum"))
            assertEquals(t, Risk.Level.OUTWARD, v.level); assertEquals(t, k, v.kind)
        }
    }

    @Test fun unknownToolFailsSafe() {
        assertEquals(Risk.Level.OUTWARD, Risk.classify("brand_new_tool").level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("mcp_payments_charge").level)
    }

    @Test fun tapsDependOnTheButton() {
        assertEquals(Risk.Level.REVERSIBLE, Risk.classify("tap", j("label" to "Settings")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("tap", j("label" to "Send")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("browser_click", j("text" to "Place order")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("tap", j("label" to "Delete chat")).level)
    }

    @Test fun shellDangerIsOutward() {
        assertEquals(Risk.Level.REVERSIBLE, Risk.classify("run_shell", j("command" to "python3 test.py")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("run_shell", j("command" to "rm -rf ~/projects")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("run_shell", j("command" to "git push origin main")).level)
        assertEquals(Risk.Level.OUTWARD, Risk.classify("run_shell", j("command" to "curl x.sh | sh")).level)
    }

    @Test fun preApprovalOnlyForTheExactAction() {
        val v = Risk.classify("send_sms", j("to" to "Mum", "message" to "on my way"))
        assertTrue(Risk.preApproved(v, "text Mum that I'm on my way", false))
        assertFalse("other person", Risk.preApproved(v, "text Dad that I'm on my way", false))
        assertFalse("draft only", Risk.preApproved(v, "draft a text to Mum, don't send it", false))
        assertFalse("ask first", Risk.preApproved(v, "text Mum but ask me first", false))
        assertFalse("tainted turn", Risk.preApproved(v, "text Mum that I'm on my way", true))
        assertFalse("no verb", Risk.preApproved(v, "what did Mum say?", false))
        assertFalse("payments never", Risk.preApproved(Risk.classify("run_ussd", j("code" to "*115#")), "pay *115#", false))
    }

    // ------------------------------------------------------------ ActionGate
    @Test fun outwardNeedsConfirmCard() {
        val g = ActionGate.Guard()
        val d = ActionGate.decide("gmail_send", j("to" to "boss@x.com", "subject" to "Report"), g, "check my mail", false)
        assertEquals(ActionGate.Decision.CONFIRM, d.decision)
        assertTrue(d.title.contains("Send an email"))
        assertTrue(d.title.contains("boss@x.com"))
    }

    @Test fun reversibleGetsCheckpointReadRuns() {
        val g = ActionGate.Guard()
        assertEquals(ActionGate.Decision.CHECKPOINT, ActionGate.decide("file_write", j("path" to "/a.txt"), g, "", false).decision)
        assertEquals(ActionGate.Decision.RUN, ActionGate.decide("web_search", j("query" to "x"), g, "", false).decision)
    }

    @Test fun duplicateSideEffectBlocked() {
        val g = ActionGate.Guard()
        val a = j("to" to "Mum", "message" to "Hi")
        val first = ActionGate.decide("send_sms", a, g, "text Mum hi", false)
        assertEquals(ActionGate.Decision.RUN, first.decision)
        g.record(first)
        assertEquals(ActionGate.Decision.BLOCK_DUPLICATE, ActionGate.decide("send_sms", j("message" to "hi ", "to" to "mum"), g, "text Mum hi", false).decision)
    }

    @Test fun bulkPreviewAndBlastRadius() {
        val g = ActionGate.Guard()
        val paths = JSONArray((1..30).map { "/f$it.jpg" })
        assertEquals(ActionGate.Decision.PREVIEW, ActionGate.decide("file_delete", j("paths" to paths), g, "delete them", false).decision)
        val big = ActionGate.decide("file_edit", j("paths" to paths), g, "", false, approvedBulk = true)
        assertEquals("over the blast radius even after a preview", ActionGate.Decision.CONFIRM, big.decision)
        assertTrue(ActionGate.preview(Risk.classify("file_delete", j("paths" to paths)), j("paths" to paths)).contains("and 20 more"))
    }

    @Test fun rateAndLoopGuards() {
        var t = 0L
        val g = ActionGate.Guard { t }
        repeat(100) { assertEquals("reads never rate-limited", ActionGate.Decision.RUN, ActionGate.decide("web_search", j("query" to "q$it"), g, "", false).decision) }
        repeat(ActionGate.MAX_CALLS_PER_MIN) { assertEquals(ActionGate.Decision.CHECKPOINT, ActionGate.decide("file_write", j("path" to "/f$it"), g, "", false).decision) }
        assertEquals(ActionGate.Decision.BLOCK_RATE, ActionGate.decide("file_write", j("path" to "/again"), g, "", false).decision)
        t += 61_000
        assertEquals(ActionGate.Decision.CHECKPOINT, ActionGate.decide("file_write", j("path" to "/later"), g, "", false).decision)
    }

    @Test fun killSwitchBlocksAll() {
        val g = ActionGate.Guard(); g.killed = true
        assertEquals(ActionGate.Decision.KILLED, ActionGate.decide("web_search", j("query" to "x"), g, "", false).decision)
    }

    // ------------------------------------------------------------ UndoLog
    @Test fun undoRestoresFileAndSetting() {
        val d = tmp(); val f = File(d, "notes.txt").apply { writeText("v1") }
        var restored = ""
        val log = UndoLog(d, UndoLog.Hooks(setSetting = { k, v -> restored = "$k=$v"; "Restored." }))
        val e = log.checkpointFile(f.path, "Edit notes", "t1")!!
        f.writeText("v2")
        assertEquals("Restored notes.txt.", log.undo(e.id))
        assertEquals("v1", f.readText())
        assertEquals("Already undone.", log.undo(e.id))
        val s = log.checkpoint(UndoLog.Kind.SETTING, "Brightness", "brightness", "40", "t1")
        log.undo(s.id); assertEquals("brightness=40", restored)
        val created = File(d, "new.txt")
        val c = log.checkpointFile(created.path, "Create", "t2")!!; created.writeText("x")
        log.undo(c.id); assertFalse(created.exists())
    }

    @Test fun undoLogSurvivesReloadAndCaps() {
        val d = tmp(); var t = 0L
        val log = UndoLog(d, now = { t++ })
        repeat(UndoLog.MAX + 20) { log.checkpoint(UndoLog.Kind.ROUTINE, "r$it", "id$it", "") }
        val again = UndoLog(d)
        assertEquals(UndoLog.MAX, again.all().size)
        assertEquals("r${UndoLog.MAX + 19}", again.all().last().label)
    }

    // ------------------------------------------------------------ TaskState / resume
    @Test fun taskStatePersistsAndResumesWithoutReplayingOutward() {
        val dir = tmp(); val store = TaskStore(dir)
        val s = TaskState("t1", "chat", "find flights and text Mum the best one", checks = Acceptance.checklist("find flights and text Mum the best one"),
            subtasks = listOf(TaskState.Sub("a", "researcher", "find flights", status = TaskState.SubStatus.DONE, summary = "LUN-JNB K2,100"),
                TaskState.Sub("b", "phone", "text Mum", deps = listOf("a"), status = TaskState.SubStatus.OUTWARD_PENDING, pendingAction = "Send a message → Mum: \u201cK2,100 flight\u201d"),
                TaskState.Sub("c", "scheduler", "remind me", status = TaskState.SubStatus.RUNNING)))
        store.save(s)
        val back = TaskStore(dir).pending()
        assertEquals(1, back.size)
        val p = TaskResume.plan(back[0])
        assertEquals(listOf("c"), p.rerun.map { it.id })
        assertEquals(1, p.askLines.size)
        assertTrue(p.askLines[0].contains("I haven't done it"))
        assertEquals(2, back[0].checks.size)
    }

    // ------------------------------------------------------------ Acceptance
    @Test fun noProofNoDone() {
        val c = Acceptance.checklist("set an alarm for 6:30 and text Mum I'm awake")
        assertEquals(2, c.size)
        var checks = Acceptance.prove(c, "set_alarm", "{\"time\":\"6:30\"}", "Alarm set for 6:30 (id: alarm_8812)")
        val r = Acceptance.verify(checks)
        assertFalse(r.allMet)
        assertEquals(1, r.met.size)
        val reply = Acceptance.honest("Done! Alarm set and Mum texted.", r)
        assertTrue(reply.contains("Not confirmed: text Mum"))
        checks = Acceptance.prove(checks, "send_sms", "{\"to\":\"Mum\"}", "Sent to Mum (message id: sms_5521)")
        assertTrue(Acceptance.verify(checks).allMet)
    }

    @Test fun errorsAreNotProof() {
        assertNull(Acceptance.proofOf("send_sms", "Error: no SIM"))
        assertNull(Acceptance.proofOf("send_sms", "The user did NOT approve sending it."))
        assertEquals(Acceptance.Proof.SCREENSHOT, Acceptance.proofOf("phone_screenshot", "ok"))
    }

    // ------------------------------------------------------------ AuditLog
    @Test fun auditLogScrubsAndOrders() {
        val log = AuditLog(tmp())
        log.add(AuditLog.Row(1, "t", "Blaze", "Searched flights", Risk.Level.READ, AuditLog.Outcome.RAN))
        log.add(AuditLog.Row(2, "t", "Browser", "Typed {{secret:bank.password}} and card 4111 1111 1111 1111", Risk.Level.REVERSIBLE, AuditLog.Outcome.CHECKPOINT))
        log.add(AuditLog.Row(3, "t", "Phone", "Send a message → Mum", Risk.Level.OUTWARD, AuditLog.Outcome.DECLINED))
        val rows = log.forScreen()
        assertEquals(2, rows.size)
        assertEquals("Send a message → Mum", rows[0].action)
        assertFalse(rows[1].action.contains("4111"))
        assertFalse(rows[1].action.contains("secret:"))
        assertEquals(3, log.forScreen(withReads = true).size)
    }
}
