package com.blaze.assistant.terminal

/**
 * v0.45.26, pure (LinuxReadyTest): the package-manager commands of the Terminal's Linuxes (Alpine apk, Ubuntu/Debian apt,
 * Arch/Manjaro pacman, Fedora dnf), used by the "ready out of the box" step ([LinuxReady]) and the backup brain's
 * runtime libraries. Self-contained in the terminal package: the on-phone Linux desktop (and its desktop package) was
 * removed in 0.45.22 and stays removed.
 */
object LinuxPm {
    enum class Pm(val tool: String) { APK("apk"), APT("apt"), PACMAN("pacman"), DNF("dnf") }

    /** apk's network timeout (seconds) when this apk supports --timeout; the probe sets `$T` for the commands that follow. */
    const val APK_TIMEOUT = 120
    const val APK_TIMEOUT_PROBE = "T=''; if apk --timeout 1 --version >/dev/null 2>&1; then T='--timeout $APK_TIMEOUT'; fi"

    /** The package manager of a Terminal Linux ([TermLogic.DISTROS] ids); anything unknown is treated as Alpine. */
    fun of(distroId: String): Pm = when (distroId.trim().lowercase()) {
        "ubuntu", "debian" -> Pm.APT
        "archlinux", "manjaro" -> Pm.PACMAN
        "fedora" -> Pm.DNF
        else -> Pm.APK
    }

    /** One-time fixes so the package manager works under PRoot, run before every step (idempotent). */
    fun prep(pm: Pm, id: String = ""): String = when (pm) {
        Pm.APK -> ""
        // A Stop mid-install leaves dpkg half-done: finish it first. Ubuntu keeps many everyday tools in universe.
        Pm.APT -> "export DEBIAN_FRONTEND=noninteractive\n" +
            (if (id == "ubuntu") "if [ -f /etc/apt/sources.list.d/ubuntu.sources ] && ! grep -q universe /etc/apt/sources.list.d/ubuntu.sources; then " +
                "sed -i 's/^Components:.*/Components: main restricted universe multiverse/' /etc/apt/sources.list.d/ubuntu.sources; fi\n" else "") +
            "dpkg --configure -a >/dev/null 2>&1 || true\n"
        // PRoot can't drop to pacman's download user or measure free space by mount point; a killed pacman leaves its lock.
        Pm.PACMAN -> "rm -f /var/lib/pacman/db.lck\n" +
            "sed -i -e 's/^DownloadUser/#DownloadUser/' -e 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf\n" +
            "if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then pacman-key --init && pacman-key --populate archlinuxarm; fi\n"
        Pm.DNF -> ""
    }

    /** Refresh the package list (pacman upgrades too: installing on a stale Arch is a partial upgrade, which can break). */
    fun refreshCmd(pm: Pm): String = when (pm) {
        Pm.APK -> "apk update"
        Pm.APT -> "apt-get -o Acquire::Retries=3 update"
        Pm.PACMAN -> "pacman -Syu --noconfirm --disable-download-timeout"
        Pm.DNF -> "dnf -y --setopt=keepcache=True makecache"
    }

    /** Installs [pkgs] without questions (already-installed ones are skipped by every manager). */
    fun installCmd(pm: Pm, pkgs: List<String>): String {
        val p = pkgs.filter { safe(it) }.joinToString(" ")
        return when (pm) {
            Pm.APK -> "apk add $p"
            Pm.APT -> "apt-get -o Acquire::Retries=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y --no-install-recommends $p"
            Pm.PACMAN -> "pacman -S --noconfirm --needed --disable-download-timeout $p"
            Pm.DNF -> "dnf -y --setopt=install_weak_deps=False --setopt=keepcache=True install $p"
        }
    }

    /** Package names only ever reach the shell as words: letters, digits and . _ + - (no leading dash). */
    fun safe(s: String): Boolean = s.isNotEmpty() && s.length < 100 && s.all { it.isLetterOrDigit() || it in "._+-" } && !s.startsWith("-") && !s.startsWith(".")
}
