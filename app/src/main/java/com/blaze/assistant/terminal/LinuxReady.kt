package com.blaze.assistant.terminal

import com.blaze.assistant.terminal.LinuxPm.Pm

/**
 * v0.45.26, pure (LinuxReadyTest): every Linux in Blaze's Terminal comes ready to use. Right after a Linux is installed
 * (and once for Linuxes installed before this version) Blaze refreshes its package lists, upgrades what's there and adds
 * a small set of everyday tools, so nobody has to start with `apk update`, `apt update`, `pacman -Sy` or `dnf makecache`:
 * python3 + pip, curl, wget, git, ca-certificates and nano. The Blaze CLI is put on PATH as `blaze` too
 * ([cliAsset], checked against the published SHA256SUMS).
 *
 * A marker file in the rootfs ([MARKER], holding [stamp]) records that it's done, so it never runs again on each launch;
 * changing the package set or [VERSION] makes it run once more. When the phone is offline or a mirror fails, nothing is
 * marked: Blaze tries again later (no sooner than [RETRY_MS]), and the Terminal works meanwhile.
 */
object LinuxReady {
    /** Bump to make every installed Linux run the ready step once more. */
    const val VERSION = 1
    /** In the rootfs (no leading slash, for File(rootfs, …)). */
    const val MARKER = ".blaze_ready"
    const val CLI_MARKER = ".blaze_cli"
    const val RETRY_MS = 6 * 3600_000L
    /** The whole step may take this long (Arch's -Syu on a slow line is the long one). */
    const val TIMEOUT_SEC = 40 * 60

    /** The everyday tools, per package manager (names checked against Alpine 3.20, Debian 12, Ubuntu 24.04, Arch ARM, Fedora 41). */
    fun packages(pm: Pm): List<String> = when (pm) {
        Pm.APK -> listOf("python3", "py3-pip", "curl", "wget", "git", "ca-certificates", "nano")
        Pm.APT -> listOf("python3", "python3-pip", "curl", "wget", "git", "ca-certificates", "nano")
        Pm.PACMAN -> listOf("python", "python-pip", "curl", "wget", "git", "ca-certificates", "nano")
        Pm.DNF -> listOf("python3", "python3-pip", "curl", "wget", "git", "ca-certificates", "nano")
    }

    /** The package manager of a Terminal Linux (Manjaro is pacman like Arch; anything unknown is treated as Alpine). */
    fun pmOf(distroId: String): Pm = when (distroId) {
        "manjaro" -> Pm.PACMAN
        else -> LinuxPm.of(distroId)
    }

    /** What the marker holds: version + package set, so a changed set runs again. */
    fun stamp(pm: Pm): String = "v$VERSION:" + packages(pm).joinToString(",")

    /** Done already? */
    fun done(marker: String?, pm: Pm): Boolean = marker?.trim() == stamp(pm)

    /** Try now? Not done, and the last failed try (0 = never) is at least [RETRY_MS] old ([force] skips the wait). */
    fun due(marker: String?, pm: Pm, lastTry: Long, now: Long, force: Boolean = false): Boolean =
        !done(marker, pm) && (force || lastTry <= 0 || now - lastTry >= RETRY_MS)

    /** Refresh, upgrade and install, then print [OK]. `set -e`: any failure ends it without the OK line. */
    const val OK = "BLAZE_READY_OK"

    fun upgradeCmd(pm: Pm): String = when (pm) {
        Pm.APK -> "apk upgrade --available"
        Pm.APT -> "apt-get -o Acquire::Retries=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -y upgrade"
        Pm.PACMAN -> ""   // refreshCmd is already -Syu (a plain -Sy + install would be a partial upgrade)
        Pm.DNF -> "dnf -y upgrade"
    }

    fun script(distroId: String): String {
        val pm = pmOf(distroId)
        return buildString {
            append("set -e\n")
            append(LinuxPm.prep(pm, distroId))
            if (pm == Pm.APK) append(LinuxPm.APK_TIMEOUT_PROBE).append('\n')
            val apkT = if (pm == Pm.APK) " \$T" else ""
            append(LinuxPm.refreshCmd(pm).let { if (pm == Pm.APK) it.replace("apk ", "apk$apkT ") else it }).append('\n')
            upgradeCmd(pm).takeIf { it.isNotEmpty() }?.let { append(if (pm == Pm.APK) it.replace("apk ", "apk$apkT ") else it).append('\n') }
            append(LinuxPm.installCmd(pm, packages(pm)).let { if (pm == Pm.APK) it.replace("apk ", "apk$apkT ") else it }).append('\n')
            // Debian/Ubuntu's pip refuses system installs (PEP 668); let `pip install x` just work in this private Linux.
            append("mkdir -p /etc && printf '[global]\\nbreak-system-packages = true\\n' > /etc/pip.conf\n")
            if (pm == Pm.APT) append("apt-get clean\n")
            if (pm == Pm.PACMAN) append("pacman -Scc --noconfirm >/dev/null 2>&1 || true\n")
            if (pm == Pm.DNF) append("dnf clean packages >/dev/null 2>&1 || true\n")
            append("command -v python3 >/dev/null && command -v git >/dev/null && command -v curl >/dev/null\n")
            append("echo $OK")
        }
    }

    /** Did the ready step finish? */
    fun ok(output: String): Boolean = output.lineSequence().any { it.trim() == OK }

    // ---------------- the Blaze CLI ----------------

    const val CLI_BASE = "https://blazeinchue.pages.dev/dl/cli/latest"
    /** Where `blaze` goes in every Linux (on the default PATH). */
    const val CLI_PATH = "usr/local/bin/blaze"

    /** The CLI build for the phone's CPU ([cpu] = "aarch64" / "arm" / null). Only linux-arm64 is published; null = skip. */
    fun cliAsset(cpu: String?): String? = when (cpu) { "aarch64" -> "blaze-linux-arm64"; else -> null }

    /** The checksum of [asset] in a SHA256SUMS file ("<hex>  <name>" lines), or null when it isn't listed / malformed. */
    fun sumFor(sums: String, asset: String): String? = sums.lineSequence().map { it.trim() }.mapNotNull { l ->
        val p = l.split(Regex("\\s+"), limit = 2)
        if (p.size == 2 && p[1].removePrefix("*") == asset && p[0].matches(Regex("[0-9a-fA-F]{64}"))) p[0].lowercase() else null
    }.firstOrNull()

    /** The CLI marker: which checksum is installed (a newer published CLI is fetched on the next ready check). */
    fun cliCurrent(marker: String?, sha: String): Boolean = marker?.trim() == sha
}
