package io.github.henry753951.airplayremote.mcp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress

/** The HTTP side of the agent server (spec 011 D2, D3): pure, so it has unit tests. */
object McpHttp {
    const val PORT = 8766
    const val PATH = "/mcp"
    const val MAX_BODY = 1 shl 20
    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 64

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class BadRequest(val status: Int, message: String) : Exception(message)

    /** Reads one request. Header names are in lower case. */
    fun read(input: InputStream): Request {
        val line = readLine(input) ?: throw BadRequest(400, "empty")
        val parts = line.split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) throw BadRequest(400, "request line")
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw BadRequest(431, "headers")
            val colon = h.indexOf(':')
            if (colon <= 0) throw BadRequest(400, "header")
            headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw BadRequest(411, "length required")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw BadRequest(400, "length") } ?: 0
        if (length < 0 || length > MAX_BODY) throw BadRequest(413, "too large")
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw BadRequest(400, "short body")
            off += n
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_LINE) throw BadRequest(431, "line")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Only the local network: loopback, link-local, private IPv4 and IPv6 unique local (FR-011). */
    fun isLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val b = address.address
        if (address is Inet6Address) {
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
            // An IPv4 address in IPv6 form (::ffff:a.b.c.d).
            if (b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) {
                return isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            }
        }
        // Public IPv4 and IPv6, and 100.64/10 of carrier NAT, are not local.
        return false
    }

    fun response(status: Int, body: String? = null, contentType: String = "application/json"): ByteArray {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        if (body != null) head.append("Content-Type: $contentType\r\n")
        head.append("Content-Length: ${bytes.size}\r\n")
        if (status == 405) head.append("Allow: POST\r\n")
        if (status == 401) head.append("WWW-Authenticate: Bearer\r\n")
        head.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.UTF_8) + bytes
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"; 431 -> "Request Header Fields Too Large"; else -> "Error"
    }
}
