package com.blaze.assistant.desktop

import java.security.SecureRandom

/**
 * v0.25.1 Linux desktop, pure part (DesktopPlanTest): what gets installed inside Blaze's Alpine Linux, the exact
 * shell lines that install / start / stop it, the VNC password, the defaults for a 3–4 GB phone, and the plain
 * progress words. A real XFCE desktop runs in proot under a TigerVNC X server (Xvnc) bound to 127.0.0.1 only, with
 * a random password; Blaze's own RFB viewer ([Rfb]) shows it full screen. All packages are free and open source
 * from Alpine's repositories, downloaded on first Start (none are bundled in the APK).
 */
object DesktopPlan {
    const val DISPLAY = ":1"
    const val PORT = 5901
    /** Light defaults for a Galaxy A12-class phone: small screen, 16-bit colour. */
    const val WIDTH = 1024
    const val HEIGHT = 576
    const val DEPTH = 16

    /** XFCE (desktop, terminal, file manager), a small browser, fonts, the VNC server and the agent's tools. */
    val PACKAGES = listOf(
        "xfce4", "xfce4-terminal", "thunar", "mousepad", "xfce4-session", "xfwm4", "xfdesktop", "xfce4-panel",
        "adwaita-icon-theme", "font-dejavu", "dbus", "dbus-x11", "tigervnc", "xdotool", "imagemagick", "dillo",
    )
    /** Download / on-disk estimates for the plain-words line before installing (measured on Alpine 3.20 aarch64, rounded up). */
    const val DOWNLOAD_MB = 260
    const val DISK_MB = 750
    /** Below this much free RAM the desktop would get killed straight away. */
    const val MIN_FREE_MB = 350

    fun supported(abi: String?): Boolean = abi != null && (abi.startsWith("arm64") || abi.startsWith("armeabi-v7a") || abi == "x86_64")

    const val UNSUPPORTED = "This phone's processor can't run Blaze's Linux, so the desktop isn't available here. Everything else in Blaze still works."

    fun sizeLine(): String = "About $DOWNLOAD_MB MB to download and $DISK_MB MB on the phone. Wi-Fi is best; it takes 5\u201315 minutes the first time."

    /** 8 letters and digits (VNC passwords use the first 8 characters). */
    fun password(rnd: SecureRandom = SecureRandom()): String {
        val abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
    }

    /**
     * v0.25.5: the packages in small batches, each its own `apk add`, so a dropped connection only costs the batch it
     * hit (what's installed stays installed; `apk add` skips it next time). Same set as [PACKAGES].
     */
    val BATCHES: List<List<String>> = listOf(
        listOf("dbus", "dbus-x11", "font-dejavu"),
        listOf("tigervnc", "xdotool"),
        listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop"),
        listOf("xfce4", "adwaita-icon-theme"),
        listOf("xfce4-terminal", "thunar", "mousepad"),
        listOf("imagemagick"),
        listOf("dillo"),
    )

    /** v0.33.4: nice-to-have batches; a package missing from the mirror skips these instead of failing the whole install. */
    val OPTIONAL: Set<List<String>> = setOf(listOf("imagemagick"), listOf("dillo"))

    /** apk waits this long with no progress before giving up on a connection (its default is 60 s). */
    const val APK_TIMEOUT = 120

    /** Sets `$T` to `--timeout 120` when this apk understands it (apk-tools 2.14+, Alpine 3.20 has 2.14.4), else nothing. */
    const val APK_TIMEOUT_PROBE = "T=''; if apk --timeout 1 --version >/dev/null 2>&1; then T='--timeout $APK_TIMEOUT'; fi"

    /** Points apk at [mirror] (`/etc/apk/repositories`). */
    fun reposLine(mirror: String): String = "mkdir -p /etc/apk && printf '%s' '" + com.blaze.assistant.terminal.AlpineNet.repositories(mirror).replace("'", "") + "' > /etc/apk/repositories"

    /** One install step: refresh the package list from [mirror] when [update], then add [pkgs] (none for the update-only step). */
    fun stepScript(mirror: String, pkgs: List<String>, update: Boolean, staged: List<DesktopPrefetch.Pkg> = emptyList()): String = buildString {
        append("set -e\n").append(reposLine(mirror)).append('\n').append(APK_TIMEOUT_PROBE).append('\n')
        // v0.33.3: apk keeps what it downloaded (a failed batch no longer throws away the packages it already fetched).
        append(APK_CACHE).append('\n')
        if (update) append("apk \$T update\n")
        if (pkgs.isNotEmpty()) append(DesktopPrefetch.installLines(pkgs, staged))
        append("echo BLAZE_STEP_OK")
    }

    /** v0.33.3: turns on apk's package cache (/etc/apk/cache → /var/cache/apk). */
    const val APK_CACHE = "mkdir -p /var/cache/apk && { [ -e /etc/apk/cache ] || ln -s /var/cache/apk /etc/apk/cache; }"

    /**
     * v0.33.5: the same command names in every Linux. Debian / Ubuntu ship Xtigervnc + tigervncpasswd (Xvnc / vncpasswd
     * come from update-alternatives, which a half-finished install may lack); ImageMagick 7 (Arch) may only have `magick`.
     * Links go in /usr/local/bin and are only made when the plain name is missing, so Alpine is untouched.
     */
    const val SHIMS = "(set +e; mkdir -p /usr/local/bin; " +
        "command -v Xvnc >/dev/null 2>&1 || { X=\$(command -v Xtigervnc) && ln -sf \"\$X\" /usr/local/bin/Xvnc; }; " +
        "command -v vncpasswd >/dev/null 2>&1 || { X=\$(command -v tigervncpasswd) && ln -sf \"\$X\" /usr/local/bin/vncpasswd; }; " +
        "command -v import >/dev/null 2>&1 || { command -v magick >/dev/null 2>&1 && printf '#!/bin/sh\\nexec magick import \"\$@\"\\n' > /usr/local/bin/import && chmod 755 /usr/local/bin/import; }; true) >/dev/null 2>&1 || true"

    /** The last step: folders the session needs, and the "installed" mark. */
    const val FINISH_SCRIPT = "set -e\nmkdir -p /root/.vnc /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix\n" + SHIMS + "\n" +
        "touch /root/.blaze_desktop_ok\necho BLAZE_DESKTOP_INSTALLED"

    /** The whole install as one script (first mirror, no retries), for reference and the agent; the app runs it step by step ([DesktopInstall]). */
    fun installScript(): String =
        stepScript(com.blaze.assistant.terminal.AlpineNet.MIRRORS[0], emptyList(), update = true) + "\n" +
            BATCHES.joinToString("\n") { "apk \$T add " + it.joinToString(" ") } + "\n" + FINISH_SCRIPT

    const val INSTALLED_MARK = "/root/.blaze_desktop_ok"


    /** The password file Xvnc reads, written with TigerVNC's own `vncpasswd -f` (the password arrives on stdin, never in argv). */
    fun passwdScript(pw: String): String = SHIMS + "\nmkdir -p /root/.vnc && printf '%s\\n' '${pw.filter { it.isLetterOrDigit() }}' | vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"

    /** Xvnc's arguments: localhost only, password auth, no TCP X11, light resolution and depth. */
    fun xvncArgs(w: Int = WIDTH, h: Int = HEIGHT, depth: Int = DEPTH): List<String> = listOf(
        "Xvnc", DISPLAY, "-geometry", "${w}x$h", "-depth", depth.toString(), "-localhost", "-rfbport", PORT.toString(),
        "-SecurityTypes", "VncAuth", "-PasswordFile", "/root/.vnc/passwd", "-nolisten", "tcp", "-AlwaysShared", "-dpi", "96",
    )

    /** Starts the X server, waits for it, then the XFCE session (the job runs as long as the session does). */
    fun startScript(w: Int = WIDTH, h: Int = HEIGHT, depth: Int = DEPTH): String =
        SHIMS + "\nrm -f /tmp/.X1-lock /tmp/.X11-unix/X1; mkdir -p /tmp/.X11-unix; chmod 1777 /tmp/.X11-unix\n" +
            xvncArgs(w, h, depth).joinToString(" ") + " >/tmp/xvnc.log 2>&1 &\n" +
            "for i in $(seq 1 40); do [ -e /tmp/.X11-unix/X1 ] && break; sleep 0.25; done\n" +
            "export DISPLAY=$DISPLAY XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p /tmp/xdg; chmod 700 /tmp/xdg\n" +
            "echo BLAZE_DESKTOP_UP\n" +
            "exec dbus-launch --exit-with-session startxfce4 >/tmp/xfce.log 2>&1"

    /** Stops everything the desktop started and frees its memory. */
    const val STOP_SCRIPT = "pkill -f xfce4-session; pkill -f xfwm4; pkill -f xfdesktop; pkill -f xfce4-panel; pkill -f Thunar; " +
        "pkill -f dillo; pkill -f dbus-daemon; pkill -f Xvnc; pkill -f Xtigervnc; rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; echo stopped"

    const val ALIVE_SCRIPT = "[ -e /tmp/.X11-unix/X1 ] && pgrep -f 'Xvnc|Xtigervnc' >/dev/null && echo up || echo down"

    // ---------- agent tool lines (xdotool + ImageMagick) ----------
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    const val SHOT_PATH = "/root/phone/.desktop_shot.png"
    fun screenshotCmd(): String = "DISPLAY=$DISPLAY import -window root -resize 1024x1024\\> $SHOT_PATH && echo ok"
    fun clickCmd(x: Int, y: Int, button: Int = 1, double: Boolean = false): String =
        "DISPLAY=$DISPLAY xdotool mousemove ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)} click ${if (double) "--repeat 2 " else ""}${button.coerceIn(1, 5)}"
    fun typeCmd(text: String): String = "DISPLAY=$DISPLAY xdotool type --delay 30 -- ${q(text.take(2000))}"
    /** Only the key names before any shell separator; anything after `;`, `&`, `|`, backtick or `$` is dropped. */
    fun keyCmd(keys: String): String = "DISPLAY=$DISPLAY xdotool key -- ${keys.substringBefore(';').substringBefore('&').substringBefore('|').substringBefore('`').substringBefore('$')
        .split(' ', ',').filter { it.matches(Regex("[A-Za-z0-9_+]{1,40}")) }.joinToString(" ")}"
    fun runCmd(app: String): String = "DISPLAY=$DISPLAY setsid ${appCommand(app)} >/dev/null 2>&1 & echo started"

    /** Friendly names to commands (anything else is run as typed, still behind the user's yes). */
    fun appCommand(app: String): String = when (app.trim().lowercase()) {
        "terminal", "xfce4-terminal" -> "xfce4-terminal"
        "files", "file manager", "thunar" -> "thunar"
        "browser", "web", "netsurf", "dillo" -> "dillo"
        "editor", "text editor", "mousepad" -> "mousepad"
        else -> app.trim()
    }

    // ---------- plain words ----------
    enum class Phase { IDLE, CHECKING, INSTALLING, STARTING, RUNNING, STOPPING, STOPPED, KILLED, ERROR, UNSUPPORTED, PAUSED }

    /** One plain line for an apk / start log line ("(12/96) Installing gtk+3.0" → "Installing gtk+3.0 (12 of 96)"). */
    fun progressLine(log: String): Pair<Int?, String>? {
        val m = Regex("""\((\d+)/(\d+)\)\s+(Installing|Upgrading)\s+(\S+)""").find(log) ?: return when {
            "fetch" in log && "APKINDEX" in log -> null to "Checking what to download\u2026"
            "BLAZE_DESKTOP_INSTALLED" in log -> 100 to "Installed."
            else -> null
        }
        val (i, n, _, pkg) = m.destructured
        val pct = (i.toInt() * 100 / n.toInt().coerceAtLeast(1)).coerceIn(0, 99)
        return pct to "Installing $pkg ($i of $n)"
    }

    const val KILLED_NOTE = "Android closed the desktop to save memory. Your files are still there. Tap Resume to start it again."
}
