package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.26: the backup brain's free-model list: filtering (free only, no Jev, no NVIDIA), order, fallback order. */
class BackupModelsTest {
    /** opencode.ai/zen/v1/models as served on 26 Oct 2026 (trimmed). */
    private val publicList = JSONObject("""{"object":"list","data":[{"id":"big-pickle"},{"id":"beam-free"},{"id":"flint-40b-free"},
        {"id":"mimo-v2.6-flash-free"},{"id":"nemotron-3.5-super-vl-free"},{"id":"nemotron-3.5-lightning-free"},{"id":"jev-1.13-free"},
        {"id":"deepseek-v4.1-flash-free"},{"id":"glm-5.3-flash-free"},{"id":"test"},{"id":"claude-sonnet-5"},{"id":"jev-1.13"},{"id":"gemini-3.5-flash"}]}""")

    @Test fun publicListKeepsOnlyFreeAllowedModelsStrongestFirst() {
        val ids = BackupModels.usable(BackupModels.fromPublic(publicList)).map { it.id }
        assertEquals(listOf("big-pickle", "glm-5.3-flash-free", "deepseek-v4.1-flash-free", "beam-free", "flint-40b-free", "mimo-v2.6-flash-free"), ids)
    }

    @Test fun jevAndNvidiaAreNeverUsed() {
        listOf("jev-1.13-free", "jev-2-free", "Jev", "nemotron-3.5-super-vl-free", "Nemotron-Ultra-free", "nvidia/llama-free").forEach {
            assertTrue(it, BackupModels.excluded(it))
        }
        assertTrue(BackupModels.excluded("x-free", "NVIDIA Llama Free"))
        listOf("big-pickle", "glm-5.3-flash-free", "beam-free", "mimo-v2.6-flash-free", "deepseek-v4.1-flash-free").forEach { assertFalse(it, BackupModels.excluded(it)) }
    }

    @Test fun serverDataDecidesFreeByPriceAndJoinsTheLiveList() {
        val server = JSONObject("""{"all":[{"id":"anthropic","models":{}},{"id":"opencode","models":{
            "big-pickle":{"id":"big-pickle","name":"Big Pickle","cost":{"input":0,"output":0},"limit":{"context":200000},"tool_call":true,"reasoning":true},
            "claude-sonnet-5":{"id":"claude-sonnet-5","cost":{"input":3,"output":15},"limit":{"context":1000000},"tool_call":true},
            "kimi-k2.5-free":{"id":"kimi-k2.5-free","cost":{"input":0,"output":0},"limit":{"context":262144},"tool_call":true},
            "nemotron-3.5-lightning-free":{"id":"nemotron-3.5-lightning-free","name":"Nemotron 3.5 Lightning Free","cost":{"input":0,"output":0}},
            "glm-5.3-flash-free":{"id":"glm-5.3-flash-free","cost":{"input":0,"output":0},"limit":{"context":200000},"tool_call":true}}}]}""")
        val s = BackupModels.fromServer(server)
        assertEquals(5, s.size)
        assertFalse(s.first { it.id == "claude-sonnet-5" }.free)
        // kimi-k2.5-free is in the server's (stale) data but not on the live list: dropped.
        val joined = BackupModels.usable(BackupModels.join(s, BackupModels.fromPublic(publicList))).map { it.id }
        assertEquals("big-pickle", joined.first())
        assertFalse(joined.contains("kimi-k2.5-free"))
        assertFalse(joined.contains("claude-sonnet-5"))
        assertFalse(joined.any { it.startsWith("nemotron") || it.startsWith("jev") })
        // Without a public list, the server's data alone is used.
        assertEquals(listOf("big-pickle", "glm-5.3-flash-free", "kimi-k2.5-free"), BackupModels.usable(BackupModels.join(s, null)).map { it.id })
        // /config/providers shape works too.
        assertEquals(5, BackupModels.fromServer(JSONObject().put("providers", server.getJSONArray("all"))).size)
    }

    /** GET /provider from opencode 1.19.4 serve without a key (26 Oct 2026, trimmed): flags under "capabilities". */
    @Test fun readsTheRealServerShape() {
        val body = JSONObject("""{"all":[{"id":"opencode","name":"OpenCode","options":{"apiKey":"public"},"models":{
            "big-pickle":{"id":"big-pickle","providerID":"opencode","name":"Big Pickle","capabilities":{"reasoning":true,"toolcall":true},"cost":{"input":0,"output":0,"cache":{"read":0,"write":0}},"limit":{"context":200000,"input":160000,"output":32000},"status":"active"},
            "beam-free":{"id":"beam-free","name":"Beam Free","capabilities":{"reasoning":true,"toolcall":true},"cost":{"input":0,"output":0},"limit":{"context":1048576},"status":"active"},
            "nemotron-3-ultra-free":{"id":"nemotron-3-ultra-free","name":"Nemotron 3 Ultra Free","capabilities":{"toolcall":true},"cost":{"input":0,"output":0},"limit":{"context":1000000},"status":"active"},
            "old-free":{"id":"old-free","capabilities":{"toolcall":true},"cost":{"input":0,"output":0},"status":"deprecated"}}}],"default":{},"connected":["opencode"]}""")
        val ms = BackupModels.fromServer(body)
        assertEquals(setOf("big-pickle", "beam-free", "nemotron-3-ultra-free"), ms.map { it.id }.toSet())
        assertEquals(true, ms.first { it.id == "big-pickle" }.toolCall)
        assertEquals(200_000, ms.first { it.id == "big-pickle" }.context)
        assertEquals(listOf("big-pickle", "beam-free"), BackupModels.usable(ms).map { it.id })   // table order beats beam's 1M window
    }

    @Test fun newModelsArePlacedFromTheirDataBelowTheKnownStrongOnes() {
        val big = BackupModels.Model("ring-2.6-1t-free", context = 262_000, toolCall = true, reasoning = true, free = true)
        val noTools = BackupModels.Model("tiny-free", context = 262_000, toolCall = false, free = true)
        val small = BackupModels.Model("x-8b-free", context = 16_000, toolCall = true, free = true)
        val known = BackupModels.fromPublic(publicList)
        val order = BackupModels.usable(known + big + noTools + small).map { it.id }
        // Unknown models land after the owner's known list (ring: 70 + window + reasoning ≈ 74 < mimo's 80) …
        assertEquals(order.indexOf("mimo-v2.6-flash-free") + 1, order.indexOf("ring-2.6-1t-free"))
        // … a tiny window sinks it, and a model that can't call tools goes last.
        assertTrue(order.indexOf("x-8b-free") > order.indexOf("ring-2.6-1t-free"))
        assertEquals("tiny-free", order.last())
    }

    @Test fun coolingModelsGoLastAndComeBack() {
        val list = BackupModels.usable(BackupModels.fromPublic(publicList))
        val now = 1_000_000L
        val order = BackupModels.order(list, mapOf("big-pickle" to now + 60_000, "glm-5.3-flash-free" to now + 30_000), now).map { it.id }
        assertEquals("deepseek-v4.1-flash-free", order.first())
        assertEquals(listOf("glm-5.3-flash-free", "big-pickle"), order.takeLast(2))   // the sooner-free one first
        assertEquals("big-pickle", BackupModels.order(list, mapOf("big-pickle" to now - 1), now).first().id)
    }

    @Test fun rateLimitsRestLongerThanPlainErrors() {
        assertEquals(10 * 60_000L, BackupModels.coolMs(429, ""))
        assertEquals(10 * 60_000L, BackupModels.coolMs(0, "Rate limit exceeded"))
        assertEquals(6 * 3600_000L, BackupModels.coolMs(404, "model not found"))
        assertEquals(2 * 60_000L, BackupModels.coolMs(500, "boom"))
    }
}
