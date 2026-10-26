package com.blaze.assistant.movies

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress

/**
 * 0.46.2 a local mock "provider site + repo" for the sandbox tests (never a real third-party site): serves
 * test/resources/movies/mockrepo (manifest.json + dist/<id>/<file>.js) and a tiny fake film site on 127.0.0.1.
 */
class MockProviderServer : AutoCloseable {
    private val root = listOf(File("src/test/resources/movies/mockrepo"), File("app/src/test/resources/movies/mockrepo")).first { it.isDirectory }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    @Volatile var hits = 0

    init {
        server.createContext("/") { ex ->
            hits++
            val path = ex.requestURI.path
            val q = ex.requestURI.rawQuery.orEmpty()
            var type = "text/html; charset=utf-8"; var code = 200
            val body: String = when {
                path == "/repo/manifest.json" -> { type = "application/json"; File(root, "manifest.json").readText() }
                path.startsWith("/repo/dist/") -> File(root, path.removePrefix("/repo/")).takeIf { it.isFile && !path.contains("..") }?.readText() ?: run { code = 404; "missing" }
                path == "/latest" || path == "/classics" -> page(listOf("The General" to "/film/1", "Nosferatu" to "/film/2"))
                path == "/search" -> if (q.contains("general", true)) page(listOf("The General" to "/film/1")) else page(emptyList())
                path == "/film/1" -> { type = "application/json"; """{"title":"The General","plot":"A train, a war, a chase.","poster":"$base/p1.jpg"}""" }
                path == "/film/2" -> { type = "application/json"; """{"title":"Mock Series","plot":"Two parts.","poster":"","series":true}""" }
                path == "/film/2/episodes" -> { type = "application/json"; """["$base/film/2/e1/play","$base/film/2/e2/play"]""" }
                path == "/redirect-private" -> { ex.responseHeaders.add("Location", "http://10.0.0.1/secret"); code = 302; "" }
                else -> { code = 404; "not found" }
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    private fun page(items: List<Pair<String, String>>) = "<html><body><div class=list>" +
        items.joinToString("") { (t, l) -> "<div class=\"film\"><a href=\"$l\">  $t </a><img src=\"$base/img$l.jpg\"></div>" } + "</div></body></html>"

    override fun close() = server.stop(0)
}
