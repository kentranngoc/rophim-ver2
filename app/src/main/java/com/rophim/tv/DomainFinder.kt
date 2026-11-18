package com.rophim.tv

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Khi link hiện tại hỏng, thử các đuôi tên miền phổ biến (rophim1.vip, rophim1.net...)
 * thông qua DNS dự phòng, trang nào mở được và có chứa từ khóa thì chọn.
 */
object DomainFinder {
    private val TLDS = listOf(
        "ink", "vip", "net", "com", "tv", "cc", "xyz", "top", "me", "io", "vn", "site",
        "online", "pro", "live", "fun", "club", "info", "org", "co", "to", "app", "cloud", "one"
    )

    /** Trả về địa chỉ gốc dạng https://host nếu tìm được, ngược lại null. */
    fun find(currentHost: String, keyword: String, proxyPort: Int): String? {
        val parts = currentHost.removePrefix("www.").split(".")
        val label = if (parts.size >= 2) parts[parts.size - 2] else parts[0]
        val labels = linkedSetOf(label, label.trimEnd { it.isDigit() }, keyword).filter { it.isNotEmpty() }
        val candidates = ArrayList<String>()
        for (t in TLDS) for (l in labels) candidates.add("$l.$t")
        candidates.remove(currentHost)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = candidates.map { host -> pool.submit(Callable { probe(host, keyword, proxyPort) }) }
            for (f in futures) {           // giữ đúng thứ tự ưu tiên
                val r = try { f.get() } catch (_: Exception) { null }
                if (r != null) return r
            }
        } finally {
            pool.shutdownNow()
        }
        return null
    }

    private fun probe(host: String, keyword: String, port: Int): String? {
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            val c = URL("https://$host/").openConnection(proxy) as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            if (c.responseCode !in 200..299) return null
            val finalHost = c.url.host
            val buf = ByteArray(200_000)
            var n = 0
            val i = c.inputStream
            while (n < buf.size) {
                val r = i.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            val text = String(buf, 0, n, Charsets.UTF_8)
            if (text.contains(keyword, ignoreCase = true) && finalHost.contains(keyword, ignoreCase = true))
                "https://$finalHost" else null
        } catch (_: Exception) {
            null
        }
    }
}
