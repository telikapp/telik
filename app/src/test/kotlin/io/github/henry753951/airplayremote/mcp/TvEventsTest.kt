package io.github.henry753951.airplayremote.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class TvEventsTest {
    @Test fun quietWaitNeedsAChangeThenQuiet() {
        val e = TvEvents()
        val before = e.version
        val t = thread { Thread.sleep(50); e.onEvent(); Thread.sleep(50); e.onEvent() }
        val w = e.waitQuiet(before, needChange = true, quietMs = 100, timeoutMs = 2000)
        t.join()
        assertTrue(w.ok)
        assertEquals(before + 2, w.version)
        assertTrue("waited ${w.ms}", w.ms >= 190)
    }

    @Test fun quietWaitTimesOutWithoutAChange() {
        val e = TvEvents()
        val w = e.waitQuiet(e.version, needChange = true, quietMs = 10, timeoutMs = 150)
        assertFalse(w.ok)
        assertTrue(w.ms >= 150)
    }

    @Test fun windowWaitChecksThePackage() {
        val e = TvEvents()
        val since = e.windowVersion
        thread { Thread.sleep(30); e.onWindow("com.other", null); Thread.sleep(30); e.onWindow("com.youtube", "com.youtube.Main") }
        val w = e.waitWindow(since, "com.youtube", 2000)
        assertTrue(w.ok)
        assertEquals("com.youtube", e.pkg)
        assertEquals("com.youtube.Main", e.activity)
        e.onWindow("com.youtube", null)
        assertEquals("a dialog keeps the activity", "com.youtube.Main", e.activity)
    }

    @Test fun checkWaitRunsOnEvents() {
        val e = TvEvents()
        var ready = false
        thread { Thread.sleep(50); ready = true; e.onEvent() }
        assertTrue(e.waitCheck(2000) { ready }.ok)
        assertFalse(e.waitCheck(100, pollMs = 20) { false }.ok)
    }
}
