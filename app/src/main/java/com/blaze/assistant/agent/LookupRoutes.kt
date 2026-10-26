package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/** 0.46.3: the lookup lane's real network (keyless search + page reads) and model (Blaze routing, the fast lane). */
object LookupRoutes {
    val net = object : LookupLane.Net {
        override suspend fun search(q: String): List<LookupLane.Hit> = LookupLane.hitsOf(Web.search(q, 6))
        override suspend fun fetch(url: String): LookupLane.Page {
            val r = Web.fetch(url, 6_000)
            val ok = !r.startsWith("Error")
            val title = r.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim().orEmpty()
            return LookupLane.Page(url, title.ifBlank { ReplySources.domain(url) }, if (ok) r else "", ok, if (ok) "" else r.take(160))
        }
    }

    /**
     * The summarize step: Blaze's own routing (the on-phone brain, then the Nous reserve, then Blaze Cloud; never NVIDIA or
     * System One), asked as a lite turn on the STEPS lane so the gateway picks its fastest capable model.
     */
    val brain = object : LookupLane.Brain {
        override suspend fun answer(q: String, pages: List<LookupLane.Page>): LookupLane.Answer {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", LookupLane.SYSTEM))
                .put(JSONObject().put("role", "user").put("content", LookupLane.prompt(q, pages)))
            val keep = LlmClient.liteTurn
            LlmClient.liteTurn = true
            try {
                val r = kotlinx.coroutines.withTimeoutOrNull(30_000L) { LlmClient.complete(msgs, null, null, null, LlmClient.Lane.STEPS) }
                    ?: throw LlmClient.LlmException(Cloud.BUSY, 503)
                val ans = LookupLane.parse(r.optString("content").substringAfterLast("</think>"), pages)
                // 0.46.4: never an answer that describes a search result when the question wasn't about a page
                return if (SmallTalk.describesPage(ans.text, q)) ans.copy(text = SmallTalk.stripPageLead(ans.text)) else ans
            } finally { LlmClient.liteTurn = keep }
        }
    }

    fun sources(pages: List<LookupLane.Page>, supporting: List<String>): ReplySources.Found {
        val pick = pages.filter { it.url in supporting }.ifEmpty { pages }
        return ReplySources.Found(pick.map { ReplySources.Source(it.url, it.title) }.take(6))
    }
}
