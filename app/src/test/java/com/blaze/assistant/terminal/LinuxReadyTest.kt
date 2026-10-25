package com.blaze.assistant.terminal

import com.blaze.assistant.terminal.LinuxPm.Pm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.26: every Terminal Linux comes refreshed, with python3/pip/curl/wget/git/ca-certificates/nano and the Blaze CLI. */
class LinuxReadyTest {
    @Test fun everyLinuxGetsTheSameLeanToolSet() {
        for (pm in Pm.values()) {
            val p = LinuxReady.packages(pm)
            assertEquals(7, p.size)
            listOf("curl", "wget", "git", "ca-certificates", "nano").forEach { assertTrue("$pm $it", it in p) }
            assertTrue(p.any { it == "python3" || it == "python" })
            assertTrue(p.any { it.endsWith("pip") })
        }
        assertEquals(Pm.APK, LinuxReady.pmOf("alpine"))
        assertEquals(Pm.APT, LinuxReady.pmOf("ubuntu"))
        assertEquals(Pm.APT, LinuxReady.pmOf("debian"))
        assertEquals(Pm.PACMAN, LinuxReady.pmOf("archlinux"))
        assertEquals(Pm.PACMAN, LinuxReady.pmOf("manjaro"))
        assertEquals(Pm.DNF, LinuxReady.pmOf("fedora"))
    }

    @Test fun scriptsRefreshUpgradeAndInstallWithoutQuestions() {
        val a = LinuxReady.script("alpine")
        assertTrue(a.startsWith("set -e\n"))
        assertTrue(a.contains("apk \$T update")); assertTrue(a.contains("apk \$T upgrade --available"))
        assertTrue(a.contains("apk \$T add python3 py3-pip curl wget git ca-certificates nano"))
        assertTrue(a.trimEnd().endsWith("echo ${LinuxReady.OK}"))
        val u = LinuxReady.script("ubuntu")
        assertTrue(u.contains("DEBIAN_FRONTEND=noninteractive")); assertTrue(u.contains("universe"))
        assertTrue(u.contains("apt-get -o Acquire::Retries=3 update")); assertTrue(u.contains(" -y upgrade"))
        assertTrue(u.contains("install -y --no-install-recommends python3 python3-pip"))
        assertTrue(u.contains("break-system-packages"))
        val arch = LinuxReady.script("archlinux")
        assertTrue(arch.contains("pacman -Syu --noconfirm")); assertTrue(arch.contains("pacman -S --noconfirm --needed"))
        assertFalse(arch.contains("pacman -Sy ")) // never a partial upgrade
        val f = LinuxReady.script("fedora")
        assertTrue(f.contains("dnf -y upgrade")); assertTrue(f.contains("dnf -y --setopt=install_weak_deps=False"))
        assertTrue(LinuxReady.ok("…\n${LinuxReady.OK}\n")); assertFalse(LinuxReady.ok("E: Unable to fetch"))
    }

    @Test fun markerMakesItRunOnceAndRetriesWait() {
        val pm = Pm.APK
        val stamp = LinuxReady.stamp(pm)
        assertTrue(LinuxReady.done(stamp, pm)); assertTrue(LinuxReady.done("$stamp\n", pm))
        assertFalse(LinuxReady.done("v0:python3", pm)); assertFalse(LinuxReady.done(null, pm))
        assertFalse(LinuxReady.due(stamp, pm, 0, 10))                       // done: never again
        assertTrue(LinuxReady.due(null, pm, 0, 10))                         // never tried
        assertFalse(LinuxReady.due(null, pm, 1_000, 1_000 + LinuxReady.RETRY_MS - 1))   // failed recently (offline): wait
        assertTrue(LinuxReady.due(null, pm, 1_000, 1_000 + LinuxReady.RETRY_MS))
        assertTrue(LinuxReady.due(null, pm, 1_000, 1_001, force = true))     // right after an install
    }

    @Test fun cliIsTheArm64BuildCheckedAgainstSha256Sums() {
        assertEquals("blaze-linux-arm64", LinuxReady.cliAsset("aarch64"))
        assertNull(LinuxReady.cliAsset("arm")); assertNull(LinuxReady.cliAsset(null))
        // SHA256SUMS as published at blazeinchue.pages.dev/dl/cli/latest/ (26 Oct 2026, trimmed).
        val sums = "67a68f784d4a33858311fcc613b892f8bab2d941b5e26b11161a096b68164960  blaze-darwin-amd64\n" +
            "2cd2c3a3a77236b391affa4d12935b9a144ca1f24bf444f3137a9215ccf300ea  blaze-linux-arm64\n" +
            "705a703f96de5adb4d6a2d716d44a9731bc2ccca89d7815e52efd69e0f376c0c  blaze-linux-amd64\n"
        assertEquals("2cd2c3a3a77236b391affa4d12935b9a144ca1f24bf444f3137a9215ccf300ea", LinuxReady.sumFor(sums, "blaze-linux-arm64"))
        assertNull(LinuxReady.sumFor(sums, "blaze-linux-arm"))
        assertNull(LinuxReady.sumFor("nothex  blaze-linux-arm64", "blaze-linux-arm64"))
        assertEquals("ab".repeat(32), LinuxReady.sumFor("${"AB".repeat(32)} *blaze-linux-arm64", "blaze-linux-arm64"))
        assertTrue(LinuxReady.cliCurrent("${"ab".repeat(32)}\n", "ab".repeat(32))); assertFalse(LinuxReady.cliCurrent(null, "x"))
        assertEquals("usr/local/bin/blaze", LinuxReady.CLI_PATH)
    }
}
