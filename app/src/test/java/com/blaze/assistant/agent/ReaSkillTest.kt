package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 0.46.2 built-in "REA: Reverse Engineer Anything" skill: ships, indexed with the right sha, loads, and is on by default. */
class ReaSkillTest {
    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val n = "reverse-engineer-anything"

    @Test fun shipsIndexedLoadsAndIsOnByDefault() {
        val md = File(root, "$n/SKILL.md").readText()
        val sha = MessageDigest.getInstance("SHA-256").digest(md.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }
        val idx = JSONObject(File(root, "index.json").readText()).getJSONArray("skills")
        val e = (0 until idx.length()).map { idx.getJSONObject(it) }.single { it.getString("name") == n }
        assertEquals(sha, e.getString("sha256"))
        assertTrue(SkillLibrary.titleOf(md).contains("Reverse Engineer Anything"))
        assertTrue(md.contains("rea-agents") && md.contains("MIT"))
        assertTrue(SkillMatch.fromDir(File(root, n), "bundled", true) != null)
        assertTrue(SkillLibrary.isOnWith(n, emptySet()))
    }
}
