package com.blaze.assistant.movies

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 0.46.2 a local mock "provider site + repo" for the sandbox tests (never a real third-party site): serves
 * test/resources/movies/mockrepo (manifest.json + dist/<id>/<file>.js) and a tiny fake film site on 127.0.0.1.
 */
class MockProviderServer : AutoCloseable {
    private val root = listOf(File("src/test/resources/movies/mockrepo"), File("app/src/test/resources/movies/mockrepo")).first { it.isDirectory }
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base: String get() = "http://127.0.0.1:${server.localPort}"
    @Volatile var hits = 0

    init {
        thread(isDaemon = true, name = "mock-provider") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { handle(s) } catch (_: Exception) { } finally { runCatching { s.close() } } }
            }
        }
    }

    // Plain HTTP/1.1 over a socket (com.sun.net.httpserver isn't on Android's unit-test classpath).
    private fun handle(s: Socket) {
        val inp = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val first = inp.readLine() ?: return
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break }
        hits++
        val target = first.split(' ').getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val q = if ('?' in target) target.substringAfter('?') else ""
        var type = "text/html; charset=utf-8"; var code = 200; var location = ""
        val body: String = when {
            path == "/repo/manifest.json" -> { type = "application/json"; File(root, "manifest.json").readText() }
            path.startsWith("/repo/dist/") -> File(root, path.removePrefix("/repo/")).takeIf { it.isFile && !path.contains("..") }?.readText() ?: run { code = 404; "missing" }
            path == "/latest" || path == "/classics" -> page(listOf("The General" to "/film/1", "Nosferatu" to "/film/2"))
            path == "/search" -> if (q.contains("general", true)) page(listOf("The General" to "/film/1")) else page(emptyList())
            path == "/film/1" -> { type = "application/json"; """{"title":"The General","plot":"A train, a war, a chase.","poster":"$base/p1.jpg"}""" }
            path == "/film/2" -> { type = "application/json"; """{"title":"Mock Series","plot":"Two parts.","poster":"","series":true}""" }
            path == "/film/2/episodes" -> { type = "application/json"; """["$base/film/2/e1/play","$base/film/2/e2/play"]""" }
            path == "/redirect-private" -> { location = "http://10.0.0.1/secret"; code = 302; "" }
            else -> { code = 404; "not found" }
        }
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 302 -> "Found"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
            (if (location.isNotEmpty()) "Location: $location\r\n" else "") + "\r\n"
        val out = s.getOutputStream(); out.write(head.toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
    }

    private fun page(items: List<Pair<String, String>>) = "<html><body><div class=list>" +
        items.joinToString("") { (t, l) -> "<div class=\"film\"><a href=\"$l\">  $t </a><img src=\"$base/img$l.jpg\"></div>" } + "</div></body></html>"

    override fun close() { runCatching { server.close() } }
}
