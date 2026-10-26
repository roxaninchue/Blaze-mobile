package com.blaze.assistant.agent

/**
 * 0.46.0 (phone screenshot): Rocky shared news with "@halo making a huge blaze update!! Modular screens and study coach, you'll
 * hear about them when they're ready!" and got back "Halo's got the modular screens and study coach locked in—just a few more
 * sparks to fire and it'll be yours." Nobody was building anything: the user was only sharing news. When the user's message
 * asks for nothing and no tool ran, sentences that promise delivery ("locked in", "on it", "coming your way", "it'll be yours",
 * "almost ready") are taken out of the reply. Pure Kotlin; NewsPromiseTest.
 */
object NewsPromise {
    private val ic = RegexOption.IGNORE_CASE
    private val request = Regex("""\?|\b(?:can you|could you|would you|will you|please|pls|help me|i need|i want|make me|set up|remind me|show me|tell me|look up)\b|""" +
        """^\s*(?:what|how|why|when|where|who|which|build|create|start|find|check|search|open|play|send|book|order|write|draw|fix|explain|make|give|get)\b""", ic)
    private val news = Regex("""\b(?:i'?m|we'?re|i am|we are|just)\s+(?:making|building|working on|planning|shipping|launching|cooking up|finishing)\b|""" +
        """\b(?:you'?ll|you will) (?:hear|see|find out|get to see)\b|\b(?:big|huge|new|major) (?:update|news|release|thing)\b|\bguess what\b|\bcoming soon\b|\bwhen (?:they'?re|it'?s) ready\b""", ic)
    /** A promise that something is being delivered / built for the user. */
    val promise = Regex("""\b(?:locked in|on it|in the works|coming (?:soon|your way|right up|to you)|(?:it|they)'?ll be (?:yours|ready|here)|be yours|""" +
        """(?:a )?(?:few|couple(?: of)?) more (?:sparks|steps|tweaks|minutes|touches)|almost (?:done|ready|there)|nearly (?:done|ready)|""" +
        """(?:i'?m|we'?re|halo'?s|blaze'?s|i am|we are) (?:building|making|working on|finishing|putting together|cooking up) (?:it|them|that|those|this|the)|""" +
        """(?:i'?ll|we'?ll|i will|we will) (?:have|deliver|ship|send|bring) (?:it|them|that)|(?:got|have) (?:it|them|that|the [\w ]{1,40}) (?:locked|covered|handled|sorted))\b""", ic)

    /** The user is sharing news, not asking for anything. */
    fun sharingNews(userText: String): Boolean {
        val t = userText.trim().replace(Regex("""^@\w+\s*"""), "")
        if (t.length < 8) return false
        return news.containsMatchIn(t) && !request.containsMatchIn(t)
    }

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+|\s+[—–]\s+|—""")).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * [reply] without promise sentences when the user only shared news and [toolsRan] is false. Null = nothing to change
     * (or everything was a promise: the caller keeps what it has rather than showing an empty bubble).
     */
    fun clean(reply: String, userText: String, toolsRan: Boolean): String? {
        if (toolsRan || !sharingNews(userText) || !promise.containsMatchIn(reply)) return null
        val kept = sentences(reply).filterNot { promise.containsMatchIn(it) }
        if (kept.isEmpty()) return null
        return kept.joinToString(" ").replace(Regex("""\s+([,.!?])"""), "$1").trim()
    }

    /** The prompt line for the duo / Halo lanes. */
    const val RULE = "\nNEWS IS NOT A TASK: when the user just shares news or plans (\"I'm making a big update…\"), react to it as yourself in the first person; " +
        "never say it is locked in, on it, coming, almost ready or being delivered, and never speak about Halo or Blaze in the third person as if they were building it."
}
