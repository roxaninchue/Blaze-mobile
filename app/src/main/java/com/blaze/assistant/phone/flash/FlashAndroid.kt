package com.blaze.assistant.phone.flash

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.blaze.assistant.agent.LlmClient
import com.blaze.assistant.phone.BlazeAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/** The real phone, through Blaze's accessibility service (no ADB). */
class A11yDevice(private val ctx: Context, private val svc: BlazeAccessibilityService) : FlashDevice {
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override fun now(): Long = android.os.SystemClock.uptimeMillis()

    override suspend fun observe(shot: Boolean): Obs {
        val s = svc.flashSnapshot() ?: return Obs("", "", 1080, 2400, emptyList()).also { nodes = emptyMap() }
        if (s.refused != null) { nodes = emptyMap(); return Obs(s.app, s.pkg, s.w, s.h, emptyList(), refused = s.refused) }
        nodes = s.items.associate { it.second.index to it.first }
        if (!shot || android.os.Build.VERSION.SDK_INT < 30) return Obs(s.app, s.pkg, s.w, s.h, s.items.map { it.second })
        val url = svc.screenshot()
        return Obs(s.app, s.pkg, s.w, s.h, s.items.map { it.second }, url, url?.let { hashOf(it) })
    }

    private fun hashOf(dataUrl: String): Long? = try {
        val bytes = android.util.Base64.decode(dataUrl.substringAfter("base64,"), android.util.Base64.DEFAULT)
        val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val small = android.graphics.Bitmap.createScaledBitmap(bm, 36, 64, true)
        val px = IntArray(36 * 64); small.getPixels(px, 0, 36, 0, 0, 36, 64)
        val grey = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        FlashHash.dhash(grey, 36, 64)
    } catch (_: Throwable) { null }

    override suspend fun act(a: PhoneAct, on: Obs): ActResult {
        com.blaze.assistant.phone.BlazeAccessibilityService.touched()
        val r = when (a) {
            is PhoneAct.Tap -> nodes[a.index]?.let { svc.flashTap(it, a.long) } ?: "No element [${a.index}]."
            is PhoneAct.TapAt -> svc.flashTapAt(FlashTree.px(a.x, on.w).toFloat(), FlashTree.px(a.y, on.h).toFloat(), a.long)
            is PhoneAct.Type -> svc.flashType(a.index?.let { nodes[it] }, a.text, a.submit)
            is PhoneAct.Scroll -> svc.flashScroll(a.index?.let { nodes[it] }, a.direction)
            is PhoneAct.Key -> if (a.name == "enter") (if (svc.flashEnter()) "Pressed Enter." else "Couldn't press Enter.") else svc.press(a.name)
            is PhoneAct.OpenApp -> com.blaze.assistant.phone.PhoneTools.run(ctx, "open_app", JSONObject().put("app_name", a.app))
            is PhoneAct.Wait -> { kotlinx.coroutines.delay(a.ms); "Waited." }
            else -> "Nothing to do."
        }
        if (a !is PhoneAct.Wait) svc.settle(1000, 150)
        val ok = !Regex("(?i)^(no element|couldn't|stopped|no text field|that's a password|that element is gone|unknown|no installed app|tried to open)").containsMatchIn(r) &&
            r != com.blaze.assistant.bank.BankModeLogic.REFUSE
        return ActResult(ok, r)
    }
}

/** The model through Blaze's own routing (Blaze Cloud gateway, then the backup brain, then the free reserve): flash steps on the STEPS lane, pro steps on MAIN. */
class RoutedPolicy : FlashPolicy {
    override suspend fun next(req: FlashRequest): String {
        val sys = if (req.mode == FlashMode.PRO) FlashPrompt.SYSTEM_PRO else FlashPrompt.SYSTEM_FLASH
        val user: Any = if (req.shot != null) JSONArray()
            .put(JSONObject().put("type", "text").put("text", FlashPrompt.user(req)))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", req.shot)))
        else FlashPrompt.user(req)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
        val lane = if (req.mode == FlashMode.PRO) LlmClient.Lane.MAIN else LlmClient.Lane.STEPS
        val r = kotlinx.coroutines.withTimeoutOrNull(if (req.mode == FlashMode.PRO) 45_000L else 25_000L) { LlmClient.complete(msgs, null, null, null, lane) }
            ?: throw IllegalStateException("model timed out")
        return r.optString("content")
    }
}

/** The `phone_task` tool: runs a whole on-screen task with the flash engine and reports honestly. */
object FlashRunner {
    const val TOOL = "phone_task"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Do a multi-step task on the phone's screen inside apps (find a setting, fill a form, navigate an app), fast: Blaze's flash engine " +
            "reads a compact numbered element list, does one step per look, checks each step worked, and switches to careful mode if a step fails. " +
            "Returns what was done, or exactly why it stopped. Use read_screen/tap only for a single quick action.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("goal", JSONObject().put("type", "string").put("description", "The task in plain words, e.g. 'turn on dark theme in Settings'"))
            .put("max_steps", JSONObject().put("type", "integer").put("description", "Step limit, default 20")))
            .put("required", JSONArray(listOf("goal")))))

    suspend fun run(ctx: Context, args: JSONObject): String {
        val goal = args.optString("goal").trim()
        if (goal.isEmpty()) return "Give the task as goal."
        val svc = BlazeAccessibilityService.instance
        if (svc == null) {
            val seen = com.blaze.assistant.agent.Settings.bool(PhoneExplainer.SEEN_KEY, false)
            val raised = PhoneExplainer.request(seen, false)
            return "Phone control is off, so nothing on the screen was touched. " + (if (raised) "Blaze is showing the user a short explainer with an Open Settings button; ask them to switch Blaze on there, then try again."
                else "The chat shows a one-tap 'Turn on' banner; ask the user to tap it, switch Blaze on, then try again.")
        }
        val steps = args.optInt("max_steps", 20).coerceIn(3, 40)
        val r = FlashLoop(A11yDevice(ctx, svc), RoutedPolicy(), steps).run(goal)
        val head = if (r.ok) "Done (${r.steps} steps, ${r.ms / 1000.0}s)." else "Not done: ${r.error}"
        return buildString {
            append(head)
            if (r.ok && r.answer.isNotBlank()) append(" ").append(r.answer.take(600))
            append("\nSteps:\n"); r.log.takeLast(12).forEach { append("- ").append(it.take(200)).append('\n') }
            if (!r.ok) append("Tell the user plainly what was done and what wasn't; don't say it worked.")
        }
    }
}
