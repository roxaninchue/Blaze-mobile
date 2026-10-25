package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.45.26, pure (BackupModelsTest): which free models the on-device backup brain may use, in what order.
 *
 * The backup brain is a headless OpenCode server inside Blaze's Alpine Linux ([BackupBrain]). OpenCode's free models
 * ("Zen" free tier) only answer through the OpenCode client, which is why it runs on the phone. Their list rotates, so
 * nothing here is a fixed model list: the live list (the server's GET /provider, or opencode.ai's public
 * /zen/v1/models as a fallback) decides what exists, and this file only filters and orders it.
 *
 * Rules (owner, 25–26 Oct 2026):
 *  - free models only (price 0 in the server's data, or the "-free" suffix / big-pickle on the public list);
 *  - never System One models (Jev, jev-*) and never NVIDIA models (Nemotron, anything nvidia);
 *  - most capable first, falling back down the list on errors and rate limits.
 *
 * Order: the static [TIERS] table (the strength order the owner listed) decides between models it knows. Models it
 * doesn't know yet (the list rotates) are placed from their data: context window, reasoning, tool calling and the size
 * in their name, on the same scale but below the known strong ones. A model whose data says it can't call tools, or
 * whose window is tiny, sinks. Recent failures move a model to the back ([order] with [cooling]).
 */
object BackupModels {
    /** The provider id of OpenCode's own hosted models inside the server. */
    const val PROVIDER = "opencode"

    data class Model(val id: String, val name: String = id, val context: Int = 0, val toolCall: Boolean? = null,
                     val reasoning: Boolean? = null, val free: Boolean = false, val score: Double = 0.0)

    /** Strongest first. Free models listed on opencode.ai, 26 Oct 2026 (Jev and Nemotron left out on purpose). */
    val TIERS: Map<String, Double> = linkedMapOf(
        "big-pickle" to 100.0,
        "glm-5.3-flash-free" to 95.0,
        "deepseek-v4.1-flash-free" to 92.0,
        "beam-free" to 88.0,
        "flint-40b-free" to 84.0,
        "mimo-v2.6-flash-free" to 80.0,
    )

    /** Ids that are free on the public list without price data. */
    private val FREE_IDS = setOf("big-pickle")

    /** System One (Jev) and NVIDIA (Nemotron / nvidia) models are never used; nor OpenCode's "test" model. */
    private val excluded = Regex("""(^|[/_.\-])(jev|nemotron|nvidia)([/_.\-0-9]|$)|^test$""", RegexOption.IGNORE_CASE)

    fun excluded(id: String, name: String = ""): Boolean =
        excluded.containsMatchIn(id) || excluded.containsMatchIn(name.replace(' ', '-')) || name.contains("nvidia", true)

    /** Free by data (input and output price 0) or, without data, by the public list's naming. */
    fun isFree(id: String, cost: JSONObject?): Boolean {
        if (cost != null && cost.has("input") && cost.has("output")) return cost.optDouble("input", 1.0) == 0.0 && cost.optDouble("output", 1.0) == 0.0
        return id.endsWith("-free") || id in FREE_IDS
    }

    /** Models of the "opencode" provider from the server's GET /provider ({all:[…]}) or GET /config/providers ({providers:[…]}). */
    fun fromServer(body: JSONObject): List<Model> {
        val list = body.optJSONArray("all") ?: body.optJSONArray("providers") ?: return emptyList()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            if (p.optString("id") != PROVIDER) continue
            val ms = p.optJSONObject("models") ?: return emptyList()
            return ms.keys().asSequence().mapNotNull { k ->
                val m = ms.optJSONObject(k) ?: return@mapNotNull null
                val id = m.optString("id").ifBlank { k }
                if (m.optString("status") == "deprecated") return@mapNotNull null
                // v1.19 nests the flags under "capabilities" (toolcall / reasoning); models.dev data has tool_call / reasoning.
                val caps = m.optJSONObject("capabilities")
                val tool = when { caps?.has("toolcall") == true -> caps.optBoolean("toolcall"); m.has("tool_call") -> m.optBoolean("tool_call"); else -> null }
                val reason = when { caps?.has("reasoning") == true -> caps.optBoolean("reasoning"); m.has("reasoning") -> m.optBoolean("reasoning"); else -> null }
                Model(id, m.optString("name").ifBlank { id }, m.optJSONObject("limit")?.optInt("context") ?: 0, tool, reason,
                    isFree(id, m.optJSONObject("cost")))
            }.toList()
        }
        return emptyList()
    }

    /** The public OpenAI-style list ({data:[{id}]}): no prices, so the naming rule decides what is free. */
    fun fromPublic(body: JSONObject): List<Model> {
        val d = body.optJSONArray("data") ?: return emptyList()
        return (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
            .map { Model(it, free = isFree(it, null)) }
    }

    /**
     * The live list's metadata joined with what is actually served: the server's data (models.dev) also carries old
     * free models that left the rotation, so when the public list is known, only ids on it stay.
     */
    fun join(server: List<Model>, public: List<Model>?): List<Model> {
        if (public == null || public.isEmpty()) return server
        if (server.isEmpty()) return public
        val live = public.map { it.id }.toSet()
        val byId = server.associateBy { it.id }
        return public.filter { it.id in live }.map { p -> byId[p.id]?.let { s -> s.copy(free = s.free || p.free) } ?: p }
    }

    private val sizeB = Regex("""(?:^|[-_/])(\d+(?:\.\d+)?)b(?:$|[-_/.])""", RegexOption.IGNORE_CASE)

    /** Strength on one scale: the table for known models; data for new ones (always below the table's top). */
    fun score(m: Model): Double {
        var s = TIERS[m.id] ?: run {
            var x = 70.0
            if (m.context > 0) x += (kotlin.math.ln(m.context / 128_000.0) / kotlin.math.ln(2.0) * 2.0).coerceIn(-6.0, 6.0)
            if (m.reasoning == true) x += 2.0
            sizeB.find(m.id)?.groupValues?.get(1)?.toDoubleOrNull()?.let { b -> x += (kotlin.math.ln(b) / kotlin.math.ln(2.0)).coerceIn(0.0, 10.0) }
            x.coerceAtMost(86.0)
        }
        if (m.toolCall == false) s -= 40.0            // Blaze's turns need tools
        if (m.context in 1 until 32_000) s -= 20.0     // Blaze's system prompt alone is several thousand tokens
        return s
    }

    /** Free, allowed models, strongest first (ties: the table's order, then id). */
    fun usable(models: List<Model>): List<Model> {
        val tierIdx = TIERS.keys.toList()
        return models.filter { it.free && !excluded(it.id, it.name) }
            .distinctBy { it.id }
            .map { it.copy(score = score(it)) }
            .sortedWith(compareByDescending<Model> { it.score }.thenBy { tierIdx.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }.thenBy { it.id })
    }

    /** Try order for one request: healthy models first (in strength order), models cooling down after them. */
    fun order(models: List<Model>, cooling: Map<String, Long>, now: Long): List<Model> {
        val (cold, hot) = models.partition { (cooling[it.id] ?: 0L) > now }
        return hot + cold.sortedBy { cooling[it.id] ?: 0L }
    }

    /** How long a model rests after a failure: a rate limit longer than a plain error. */
    fun coolMs(status: Int, message: String): Long {
        val t = message.lowercase()
        return when {
            status == 429 || "rate limit" in t || "too many" in t || "quota" in t -> 10 * 60_000L
            status == 404 || "not found" in t || "no longer" in t || "unknown model" in t -> 6 * 3600_000L
            status == 401 || status == 403 -> 30 * 60_000L
            else -> 2 * 60_000L
        }
    }

    /** JSON for diagnostics / the cache file. */
    fun toJson(models: List<Model>): JSONArray = JSONArray().apply {
        models.forEach { put(JSONObject().put("id", it.id).put("context", it.context).put("score", it.score)) }
    }
}
