package io.github.henry753951.airplayremote.mcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class McpHttpTest {
    private fun read(raw: String) = McpHttp.read(raw.byteInputStream())

    @Test fun readsARequestWithABody() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val r = read("POST /mcp?x=1 HTTP/1.1\r\nHost: tv\r\nAuthorization: Bearer T\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals("POST", r.method)
        assertEquals("/mcp", r.path)
        assertEquals("Bearer T", r.header("authorization"))
        assertArrayEquals(body.toByteArray(), r.body)
    }

    @Test fun refusesBadRequests() {
        fun status(raw: String) = try { read(raw); fail("no error"); 0 } catch (e: McpHttp.BadRequest) { e.status }
        assertEquals(400, status(""))
        assertEquals(400, status("GARBAGE\r\n\r\n"))
        assertEquals(413, status("POST /mcp HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n"))
        assertEquals(411, status("POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
    }

    @Test fun acceptsOnlyLocalAddresses() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.0.13", "169.254.1.1", "::1", "fe80::1", "fd12::5", "::ffff:192.168.1.5")) {
            assertTrue(a, McpHttp.isLocal(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "100.64.0.1", "172.32.0.1", "2001:4860::8888", "::ffff:8.8.8.8")) {
            assertFalse(a, McpHttp.isLocal(InetAddress.getByName(a)))
        }
    }

    @Test fun writesAResponse() {
        val text = String(McpHttp.response(200, "{}"))
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{}"))
        assertTrue(String(McpHttp.response(401)).contains("WWW-Authenticate: Bearer"))
    }
}
