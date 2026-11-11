package com.example.queststats

import java.net.ServerSocket
import java.net.Socket

/**
 * Optional tiny HTTP server so you can watch stats and predictions from a phone/PC browser on the
 * same Wi-Fi (http://<quest-ip>:8765). Off by default; it exposes data to your local network.
 */
class MiniServer(private val port: Int = 8765) {
    @Volatile private var server: ServerSocket? = null

    private val page = """
        <!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
        <title>QuestStats</title>
        <body style="background:#111;color:#8f8;font:16px monospace;padding:12px">
        <pre id=o>loading...</pre>
        <script>
        async function t(){try{const r=await fetch('/text');document.getElementById('o').textContent=await r.text()}catch(e){}}
        t();setInterval(t,1000);
        </script>
    """.trimIndent()

    fun start() {
        if (server != null) return
        Thread {
            try {
                val s = ServerSocket(port)
                server = s
                while (!s.isClosed) {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                }
            } catch (e: Throwable) { /* closed or port busy */ }
        }.start()
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 3000
            val req = c.getInputStream().bufferedReader().readLine() ?: ""
            val path = req.split(" ").getOrNull(1) ?: "/"
            val (type, body) = when (path) {
                "/metrics" -> "application/json" to SharedState.json
                "/text" -> "text/plain" to (SharedState.statsText + "\n\n" + SharedState.predictionText)
                else -> "text/html" to page
            }
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        } catch (e: Throwable) {
        } finally {
            try { c.close() } catch (e: Throwable) {}
        }
    }

    fun stop() {
        try { server?.close() } catch (e: Throwable) {}
        server = null
    }
}
