package com.rophim.tv

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * Proxy cục bộ (127.0.0.1) chỉ dùng khi link chính bị hỏng.
 * Nó tự phân giải tên miền qua DNS-over-HTTPS (1.1.1.1 / 8.8.8.8) thay cho DNS của nhà mạng,
 * giống như bật ứng dụng 1.1.1.1, nhưng chỉ nằm trong app.
 */
class DohProxy {
    private val pool = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private var server: ServerSocket? = null

    @Volatile var port: Int = 0
        private set

    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ---------- DoH ----------
    fun resolve(host: String): List<InetAddress> {
        if (host.matches(Regex("^[0-9.]+$"))) return listOf(InetAddress.getByName(host))
        val now = System.currentTimeMillis()
        cache[host]?.let { if (now - it.first < 300_000) return it.second }
        val servers = listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query", "https://8.8.8.8/resolve")
        for (base in servers) {
            try {
                val u = URL("$base?name=$host&type=A")
                val conn = u.openConnection() as HttpsURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("accept", "application/dns-json")
                val body = conn.inputStream.bufferedReader().readText()
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                val list = ArrayList<InetAddress>()
                for (i in 0 until answers.length()) {
                    val a = answers.getJSONObject(i)
                    if (a.optInt("type") == 1) list.add(InetAddress.getByName(a.getString("data")))
                }
                if (list.isNotEmpty()) {
                    cache[host] = now to list
                    return list
                }
            } catch (_: Exception) {
            }
        }
        throw java.net.UnknownHostException(host)
    }

    private fun connect(host: String, port: Int): Socket {
        var last: Exception? = null
        for (ip in resolve(host)) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 8000)
                return s
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.net.UnknownHostException(host)
    }

    // ---------- Proxy ----------
    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            val first = readLine(cin) ?: return client.close()
            val parts = first.split(" ")
            if (parts.size < 3) return client.close()
            val headers = ArrayList<String>()
            while (true) {
                val l = readLine(cin) ?: break
                if (l.isEmpty()) break
                headers.add(l)
            }
            if (parts[0].equals("CONNECT", true)) {
                val hp = parts[1].split(":")
                val remote = connect(hp[0], hp.getOrNull(1)?.toIntOrNull() ?: 443)
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                cout.flush()
                remote.tcpNoDelay = true
                // Nhà mạng hay đọc tên trang trong gói TLS đầu tiên để chặn → tách làm 2 gói nhỏ
                val hello = ByteArray(16 * 1024)
                val n = cin.read(hello)
                if (n <= 0) { client.close(); remote.close(); return }
                val ro = remote.getOutputStream()
                if (n > 6 && hello[0].toInt() == 0x16) {
                    ro.write(hello, 0, 3); ro.flush()
                    Thread.sleep(25)
                    ro.write(hello, 3, n - 3); ro.flush()
                } else {
                    ro.write(hello, 0, n); ro.flush()
                }
                pipe(client, remote)
            } else {
                // HTTP thường (http://host/path): chuyển tiếp, đóng kết nối sau mỗi yêu cầu
                val url = URL(parts[1])
                val remote = connect(url.host, if (url.port > 0) url.port else 80)
                val path = (if (url.path.isEmpty()) "/" else url.path) + (if (url.query != null) "?${url.query}" else "")
                val ro = remote.getOutputStream()
                ro.write("${parts[0]} $path ${parts[2]}\r\n".toByteArray())
                for (h in headers) {
                    val k = h.substringBefore(":").lowercase()
                    if (k == "proxy-connection" || k == "connection") continue
                    ro.write((h + "\r\n").toByteArray())
                }
                ro.write("Connection: close\r\n\r\n".toByteArray())
                ro.flush()
                pipe(client, remote)
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(a: Socket, b: Socket) {
        val t = Thread { copy(a.getInputStream(), b.getOutputStream(), a, b) }
        t.isDaemon = true
        t.start()
        copy(b.getInputStream(), a.getOutputStream(), a, b)
    }

    private fun copy(i: InputStream, o: OutputStream, a: Socket, b: Socket) {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { a.close() } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
        }
    }
}
