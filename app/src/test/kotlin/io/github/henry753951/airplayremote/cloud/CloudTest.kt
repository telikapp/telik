package io.github.henry753951.airplayremote.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CloudTest {
    @Test fun readsTheCloudflaredOutput() {
        assertTrue(CloudLog.connected("2026-11-25T08:38:08Z INF Registered tunnel connection connIndex=0 connection=0b1 event=0 ip=198.41.200.193 location=vie06 protocol=quic"))
        val config = """2026-11-25T09:00:00Z INF Updated to new configuration config="{\"ingress\":[{\"hostname\":\"tv.slim-shaggy.com\", \"service\":\"http://localhost:8766\"}, {\"service\":\"http_status:404\"}], \"warp-routing\":{\"enabled\":false}}" version=1"""
        assertEquals("tv.slim-shaggy.com", CloudLog.hostname(config))
        assertNull(CloudLog.hostname("INF Starting metrics server on 127.0.0.1:20241/metrics"))
        assertEquals("Unauthorized: Invalid tunnel secret", CloudLog.error("2026-11-25T09:00:00Z ERR Unauthorized: Invalid tunnel secret"))
        assertNull(CloudLog.error("2026-11-25T09:00:00Z INF fine"))
    }

    @Test fun edgeAddressesBecomeArguments() {
        assertEquals(listOf("--edge", "198.41.192.27:7844", "--edge", "198.41.200.53:7844"),
            CloudLog.edgeArgs(listOf("198.41.192.27", "198.41.200.53", "198.41.192.27")))
        assertEquals(emptyList<String>(), CloudLog.edgeArgs(emptyList()))
        assertEquals(16, CloudLog.edgeArgs(List(20) { "10.0.0.$it" }).size)
    }

    @Test fun backoffGrowsToFiveMinutes() {
        assertEquals(5_000L, CloudLog.backoffMs(0))
        assertEquals(10_000L, CloudLog.backoffMs(1))
        assertEquals(300_000L, CloudLog.backoffMs(10))
    }

    @Test fun checksTheTunnelTokenShape() {
        val good = Base64.getEncoder().encodeToString("""{"a":"98a1725dbd6f8cac3678f06498149efa","t":"99229c47-d1d7-4aee-b0a0-e945ee99bfd6","s":"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0"}""".toByteArray())
        assertTrue(CloudToken.valid(good))
        assertEquals("99229c47-d1d7-4aee-b0a0-e945ee99bfd6", CloudToken.tunnelId(good))
        assertFalse(CloudToken.valid("short"))
        assertFalse(CloudToken.valid(Base64.getEncoder().encodeToString("""{"a":"x","t":"y","pad":"${"z".repeat(100)}"}""".toByteArray())))
        assertFalse(CloudToken.valid("$good extra"))
    }

    @Test fun setupCodeWorksOnceAndExpires() {
        var now = 0L
        val c = SetupCode({ now })
        val code = c.renew()
        assertEquals(6, code.length)
        assertEquals(code, c.current())
        assertFalse(c.use("000000".takeIf { it != code } ?: "111111"))
        assertTrue(c.use(code))
        assertFalse(c.use(code))
        val next = c.renew()
        now += SetupCode.TTL_MS
        assertFalse(c.use(next))
    }

    @Test fun fiveWrongCodesEndTheCode() {
        val c = SetupCode()
        val code = c.renew()
        val wrong = if (code == "123456") "654321" else "123456"
        repeat(5) { assertFalse(c.use(wrong)) }
        assertFalse(c.use(code))
    }
}
