package com.blaze.assistant.agent

/**
 * 0.46.4 (Rocky 19:41: "what's up??" ran the web lookup lane and came back as "The page lists 'what's up?' among the
 * idioms…" from a dictionary page, with a source pill and Go deeper). A cheap pre-classifier, rules only:
 *  - CHAT: greetings, small talk, thanks, acks, laughter, emoji, "how are you", "what's up" and other short conversational
 *    messages. Never the lookup lane, never search / fetch / tools, never source pills or Go deeper: the chat lane answers
 *    in 1-2 warm sentences using the conversation.
 *  - LOOKUP: a question that needs current, external or verifiable facts (prices, news, scores, people, places, dates…).
 *  - UNSURE: everything else. The fast lookup lane is NOT run; the normal turn goes on and the model itself decides whether
 *    it needs to search (the "model only if unsure" tier).
 * Pure (SmallTalkTest).
 */
object SmallTalk {
    enum class Kind { CHAT, LOOKUP, UNSURE }

    private val emojiOnly = Regex("""^[\p{So}\p{Sk}\p{Cs}\p{Mn}\u200d\ufe0f\s!?.:;()<>3^_*~-]+$""")
    private val chat = Regex("""(?i)^(?:@?(?:blaze|halo)[,!\s]+)?(?:hi+|hey+|hello+|helo|yo+|sup|wa+s+u+p+|wazzup|what'?s ?up|whats ?good|what'?s (?:new|good|happening|going on)|hiya|howdy|heya|gm|gn|good (?:morning|afternoon|evening|night|day)|morning|evening|night|how (?:are|r) (?:you|u|ya)(?: doing| today| now)?|how(?:'?s| is) (?:it going|your day|everything|life|you)|how (?:have you been|do you do|you doing)|you (?:good|ok|okay|there|awake)|are you (?:there|ok|okay|awake|good)|thanks?(?: (?:you|u|a lot|so much|man|bro|blaze|halo))*|thank you(?: so much| very much)?|thx|ty|tysm|cheers|appreciate (?:it|you)|ok(?:ay|ie)?|k+|kk|alright|aight|sure|cool|nice|great|awesome|perfect|sweet|neat|wow|whoa|oh|ohh+|ah+|hmm+|mm+|yeah|yep|yup|yes|no|nope|nah|lol+|lmao+|rofl|haha+|hehe+|xd|bye|goodbye|see (?:you|ya)(?: later| soon)?|later|ttyl|good ?night|sweet dreams|love (?:you|u|it|this)|miss (?:you|u)|you'?re (?:the best|awesome|great|amazing|funny|cool)|good (?:job|work|bot)|well done|nice one|my bad|sorry|no worries|np|i'?m (?:good|fine|ok|okay|great|tired|bored|back|here|home)|just (?:chilling|bored|saying hi)|nothing much|not much|same|me too|bruh|dude|bro|man)\b[\s!?.,~:;)(*^\p{So}\p{Sk}\u200d\ufe0f-]*(?:(?:blaze|halo|bro|man|dude|buddy|mate|friend)[\s!?.,~\p{So}\ufe0f]*)?$""")
    /** Words that make even a short message about the world (so not small talk). */
    private val worldly = Regex("""(?i)\b(?:price|cost|news|score|weather|forecast|stock|bitcoin|btc|rate|election|president|minister|capital|population|released?|launch|when is|who is|who won|what is the|how much|how many|define|meaning of|latest|current|today'?s|tonight|tomorrow|yesterday|\d{4})\b""")

    fun classify(text: String): Kind {
        val t = text.trim()
        if (t.isEmpty()) return Kind.CHAT
        if (ruleChat(t)) return Kind.CHAT
        if (LookupLane.isLookup(t) && worldly.containsMatchIn(t)) return Kind.LOOKUP
        return Kind.UNSURE
    }

    fun chitChat(text: String): Boolean = classify(text) == Kind.CHAT

    private val ackRx = Regex("""(?i)^(?:ok(?:ay|ie)?|k+|kk|alright|aight|sure|yeah|yep|yup|yes|no|nope|nah|go ahead|do it|please)\b[\s!?.,]*$""")
    /** A bare yes / no / ok: after a question from Blaze it answers that question (part of a task), so it keeps the full lane. */
    fun ack(text: String): Boolean = ackRx.matches(text.trim())

    /** The rule tier alone (no lookup check; LookupLane.isLookup calls this). */
    fun ruleChat(text: String): Boolean { val t = text.trim()
        return t.isEmpty() || emojiOnly.matches(t) || (t.length <= 60 && chat.matches(t) && !worldly.containsMatchIn(t)) }

    /** The chat lane's instruction for small talk: warm, short, from the conversation, no tools or sources. */
    const val RULE = "This is small talk. Reply directly and warmly in 1-2 sentences, in your own voice, using the conversation so far. " +
        "Do not search, cite pages or sources, or describe a web page."

    private val pageLead = Regex("""(?i)^(?:the|this|that|one) (?:page|site|website|article|source|result|dictionary|entry)s? (?:lists?|says?|states?|shows?|notes?|mentions?|explains?|describes?|defines?|reports?)(?: that)?\s+""")

    /** An answer that describes a search result ("The page lists…") when the question wasn't about a page. */
    fun describesPage(answer: String, question: String): Boolean =
        pageLead.containsMatchIn(answer.trim()) && !Regex("""(?i)\b(?:page|site|website|article|link|url|source)\b""").containsMatchIn(question)

    /** Drops a leading "The page says that …" and capitalises what's left (the lookup lane's last guard). */
    fun stripPageLead(answer: String): String {
        val r = answer.trim().replaceFirst(pageLead, "")
        return if (r.isEmpty()) answer else r.replaceFirstChar { it.uppercaseChar() }
    }
}
