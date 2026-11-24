package io.github.henry753951.airplayremote.mcp

/**
 * The screen events from the accessibility service and the waits of the agent (spec 011 D7). Each event makes
 * [version] grow. The clock is a parameter, so the waits have unit tests.
 */
class TvEvents(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Object()
    @Volatile var version = 0L; private set
    @Volatile var windowVersion = 0L; private set
    @Volatile var lastAt = clock(); private set
    @Volatile var pkg: String? = null; private set
    @Volatile var activity: String? = null; private set

    fun onEvent() = synchronized(lock) {
        version++
        lastAt = clock()
        lock.notifyAll()
    }

    /** A window state change. [activity] is null when the class is not an activity, for example a dialog. */
    fun onWindow(pkg: String, activity: String?) = synchronized(lock) {
        this.pkg = pkg
        if (activity != null) this.activity = activity
        windowVersion++
        version++
        lastAt = clock()
        lock.notifyAll()
    }

    data class Wait(val ok: Boolean, val ms: Long, val version: Long)

    /** Until the version is above [since] (if [needChange]) and no event came for [quietMs]. */
    fun waitQuiet(since: Long, needChange: Boolean, quietMs: Long, timeoutMs: Long): Wait =
        waitFor(timeoutMs) { now -> (!needChange || version > since) && now - lastAt >= quietMs }

    /** Until a window change after [since], for [pkg] if it is given. */
    fun waitWindow(since: Long, pkg: String?, timeoutMs: Long): Wait =
        waitFor(timeoutMs) { _ -> windowVersion > since && (pkg == null || this.pkg == pkg) }

    /** Until [check] is true. It runs at the start, after events, and each [pollMs] at most. */
    fun waitCheck(timeoutMs: Long, pollMs: Long = 200, check: () -> Boolean): Wait {
        val start = clock()
        var seen = -1L
        while (true) {
            if (version != seen) {
                seen = version
                if (check()) return Wait(true, clock() - start, version)
            }
            val left = timeoutMs - (clock() - start)
            if (left <= 0) return Wait(false, clock() - start, version)
            synchronized(lock) { if (version == seen) lock.wait(minOf(left, pollMs).coerceAtLeast(1)) }
            if (version == seen && check()) return Wait(true, clock() - start, version)
        }
    }

    private fun waitFor(timeoutMs: Long, done: (Long) -> Boolean): Wait {
        val start = clock()
        synchronized(lock) {
            while (true) {
                val now = clock()
                if (done(now)) return Wait(true, now - start, version)
                val left = timeoutMs - (now - start)
                if (left <= 0) return Wait(false, now - start, version)
                // Wake up at the end of the quiet time too, not only on an event.
                lock.wait(minOf(left, 50L).coerceAtLeast(1))
            }
        }
    }
}
