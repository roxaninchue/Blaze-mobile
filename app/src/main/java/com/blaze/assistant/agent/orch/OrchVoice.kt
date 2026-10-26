package com.blaze.assistant.agent.orch

/**
 * 0.47.0 the words around a specialist run (pure; OneBlazeTest): its system prompt (its brief, the goal pin, the scoped
 * rules, the active personality's voice rule and the mood) and the filter on its final reply, so every part of a task
 * speaks in the one Blaze voice and none of them says "sir", claims an unproven result or asks for a secret in chat.
 */
object OrchVoice {
    const val SAFETY = "\nRULES: you only have the tools you were given. Read-only actions are free. Anything that sends, posts, pays, deletes, " +
        "installs or changes an account shows the user a confirm card first; never try to get around it. Prefer drafts to sends. " +
        "Never ask for a password, code or card in chat: use request_secret / secure-form handles. Stop and report after two failed tries. " +
        "Finish with a short summary: what you did, what you saw as proof (a readback, a screenshot, an id), and anything that didn't work."

    fun system(specialist: String, butler: Boolean, name: String?, tone: Mood.Tone, goal: String = "", constraints: List<String> = emptyList(),
               inputs: Map<String, String> = emptyMap(), now: String = ""): String = buildString {
        val d = SpecialistKit.byId(specialist)
        append("You are Blaze, doing one part of the user's task (").append(d?.label ?: specialist).append("). ").append(d?.brief.orEmpty())
        if (now.isNotBlank()) append("\nNow: ").append(now).append('.')
        if (goal.isNotBlank()) append("\nTHE USER'S GOAL (pinned): ").append(goal.take(600))
        if (constraints.isNotEmpty()) append("\nCONSTRAINTS: ").append(constraints.joinToString("; ").take(600))
        if (inputs.isNotEmpty()) { append("\nRESULTS FROM EARLIER PARTS:"); inputs.forEach { (k, v) -> append("\n- ").append(k).append(": ").append(v.take(800)) } }
        append(SAFETY)
        if (butler) append(Butler.voiceRule(name, tone)) else append("\n").append(Mood.rule(tone).replace("One dry, understated line is fine when it fits, never at the user's expense.", "Keep it brief."))
    }

    /** The specialist's final text as it may reach the orchestrator / the user. */
    fun finalReply(text: String, butler: Boolean, name: String?, tone: Mood.Tone): String {
        var t = text.trim()
        t = Butler.scrub(t, name)     // never sir / madam, whatever the personality
        if (butler || !Mood.witAllowed(tone)) t = Mood.dewit(t, tone)
        return t.take(1200)
    }
}
