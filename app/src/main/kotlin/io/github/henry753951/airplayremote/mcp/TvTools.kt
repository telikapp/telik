package io.github.henry753951.airplayremote.mcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import io.github.henry753951.airplayremote.launcher.AppRepository
import io.github.henry753951.airplayremote.remote.input.RemoteAccessibilityService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The Android side of the MCP tools (spec 011 D6). Each tool says in its error what is missing. */
class TvTools(private val context: Context, private val events: TvEvents) {
    private val main = Handler(Looper.getMainLooper())
    private val pm: PackageManager = context.packageManager
    private val actionLock = Any()

    private class Snapshot(val nodes: List<UiNode>, val infos: List<AccessibilityNodeInfo>, val pkg: String?)
    @Volatile private var last: Snapshot? = null

    val all: List<McpTool> = listOf(
        McpTool("screen", "Current app/activity and compact UI tree: #id role \"text\" id=viewId [l,t,r,b] flags.",
            listOf("max?" to "integer:node limit, default 150")) { a -> screen(a.intOrNull("max") ?: 150) },
        McpTool("find", "Find nodes by text, description or view id (part, no case). Gives ids for act.",
            listOf("query" to "string:text to find", "max?" to "integer:default 20")) { a -> find(a.str("query"), a.intOrNull("max") ?: 20) },
        McpTool("act", "Do an action on a node id from screen/find. Click goes to the nearest clickable parent.",
            listOf("node" to "integer:node id",
                "action" to "enum(click|long_click|focus|select|scroll_forward|scroll_backward|scroll_up|scroll_down|scroll_left|scroll_right|set_text|clear):action",
                "text?" to "string:for set_text"), action = true) { a -> act(a.intOrNull("node") ?: throw ToolError("missing node"), a.str("action"), a.strOrNull("text")) },
        McpTool("key", "Press a remote key.",
            listOf("key" to "enum(up|down|left|right|ok|back|home|play_pause|volume_up|volume_down|mute|voice):key (voice starts voice search)",
                "repeat?" to "integer:1-20, default 1"), action = true) { a -> key(a.str("key"), (a.intOrNull("repeat") ?: 1).coerceIn(1, 20)) },
        McpTool("tap", "Tap at screen coordinates, or swipe to x2,y2. Fallback when a node action fails.",
            listOf("x" to "integer", "y" to "integer", "x2?" to "integer", "y2?" to "integer", "ms?" to "integer:swipe time, default 300"),
            action = true) { a -> tap(a.intOrNull("x") ?: throw ToolError("missing x"), a.intOrNull("y") ?: throw ToolError("missing y"), a.intOrNull("x2"), a.intOrNull("y2"), a.intOrNull("ms") ?: 300) },
        McpTool("type", "Type text into the focused text field; submit presses the keyboard action (search/done).",
            listOf("text" to "string", "submit?" to "boolean:default false"), action = true) { a -> type(a.optString("text", ""), a.boolOr("submit", false)) },
        McpTool("wait", "Wait for: change (screen changes, then quiet), window (new window/app), text (a node with text shows).",
            listOf("for" to "enum(change|window|text):what to wait for", "text?" to "string:for text", "package?" to "string:for window",
                "since?" to "integer:screen version v, default now", "timeout_ms?" to "integer:default 5000, max 30000")) { a -> wait(a) },
        McpTool("apps", "Launchable apps: package, name, version.") { _ -> apps() },

        McpTool("launch", "Open an app by package.", listOf("package" to "string"), action = true) { a -> launch(a.str("package")) },






    )

    fun release() {
        last = null
        RemoteAccessibilityService.instance?.let { s -> runCatching { setViewIds(s, false) } }
    }

    // ---- Screen ----

    private fun service(): RemoteAccessibilityService = RemoteAccessibilityService.instance
        ?: throw ToolError("Telik accessibility access is off. Turn it on in the TV settings: Accessibility > Telik.")

    private fun setViewIds(s: AccessibilityService, on: Boolean) {
        val info = s.serviceInfo ?: return
        val has = info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0
        if (has == on) return
        info.flags = if (on) info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS else info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS.inv()
        s.serviceInfo = info
    }

    private fun snapshot(): Snapshot {
        val s = service()
        setViewIds(s, true)
        val windows = runCatching { s.windows }.getOrDefault(emptyList())
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.layer })
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(s.rootInActiveWindow) }
        val nodes = ArrayList<UiNode>()
        val infos = ArrayList<AccessibilityNodeInfo>()
        val rect = Rect()
        var visited = 0
        for (root in roots) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty() && visited < MAX_VISIT) {
                val n = stack.removeLast()
                visited++
                n.getBoundsInScreen(rect)
                val u = UiNode(
                    cls = n.className?.toString().orEmpty(),
                    text = n.text?.toString(),
                    desc = n.contentDescription?.toString(),
                    viewId = n.viewIdResourceName,
                    left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                    visible = n.isVisibleToUser, focused = n.isFocused || n.isAccessibilityFocused,
                    clickable = n.isClickable, focusable = n.isFocusable, scrollable = n.isScrollable,
                    editable = n.isEditable, checkable = n.isCheckable, checked = n.isChecked,
                    selected = n.isSelected, enabled = n.isEnabled, hint = n.hintText?.toString(),
                )
                if (UiTree.keep(u)) { nodes += u; infos += n }
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
            }
        }
        val pkg = s.rootInActiveWindow?.packageName?.toString() ?: events.pkg
        return Snapshot(nodes, infos, pkg).also { last = it }
    }

    private fun header(pkg: String?): String {
        val act = events.activity?.takeIf { pkg != null && it.startsWith(pkg) }?.removePrefix(pkg ?: "")?.let { "/$it" }
            ?: events.activity?.takeIf { events.pkg == pkg }?.let { "/$it" } ?: ""
        return "app=${pkg ?: "?"}$act v=${events.version}"
    }

    private fun screen(max: Int): ToolResult {
        val s = snapshot()
        return ToolResult(UiTree.render(header(s.pkg) + " nodes=${s.nodes.size}", s.nodes, s.pkg, max.coerceIn(1, 1000)))
    }

    private fun find(query: String, max: Int): ToolResult {
        val s = snapshot()
        val hits = s.nodes.indices.filter { UiTree.matches(s.nodes[it], query) }
        if (hits.isEmpty()) return ToolResult(header(s.pkg) + "\nnot found: $query")
        return ToolResult(UiTree.render(header(s.pkg) + " found=${hits.size}", s.nodes, s.pkg, max.coerceIn(1, 200), hits))
    }

    // ---- Actions ----

    /** Runs an action, waits until the screen is quiet, and gives the new version and the app. */
    private fun settle(timeoutMs: Long = SETTLE_MS, block: () -> String): ToolResult = synchronized(actionLock) {
        val before = events.version
        val what = block()
        val w = events.waitQuiet(before, needChange = true, quietMs = QUIET_MS, timeoutMs = timeoutMs)
        val pkg = RemoteAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: events.pkg
        // Apps that draw their own UI (YouTube Cobalt, Apple TV) send no accessibility events.
        ToolResult("$what; ${if (w.ok) "screen changed in ${w.ms} ms" else "no accessibility event"}; ${header(pkg)}")
    }

    private fun node(id: Int): AccessibilityNodeInfo {
        val s = last ?: throw ToolError("no screen yet: call screen or find first")
        val n = s.infos.getOrNull(id) ?: throw ToolError("no node #$id in the last screen")
        if (!n.refresh() || !n.isVisibleToUser) throw ToolError("stale: node #$id is gone; call screen again")
        return n
    }

    private fun up(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 8) {
            if (test(c)) return c
            c = c.parent
            depth++
        }
        return null
    }

    private fun act(id: Int, action: String, text: String?): ToolResult {
        val n = node(id)
        return settle {
            when (action) {
                "click", "long_click" -> {
                    val long = action == "long_click"
                    val target = up(n) { if (long) it.isLongClickable else it.isClickable }
                    val ok = target?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (ok) "$action #$id" else {
                        val r = Rect().also(n::getBoundsInScreen)
                        gesture(r.exactCenterX(), r.exactCenterY(), null, null, if (long) 800 else 60)
                        "$action #$id by tap at ${r.centerX()},${r.centerY()} (no node action)"
                    }
                }
                "focus" -> {
                    val t = up(n) { it.isFocusable } ?: n
                    if (!t.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) throw ToolError("focus failed: use key up/down/left/right")
                    "focus #$id"
                }
                "select" -> { if (!n.performAction(AccessibilityNodeInfo.ACTION_SELECT)) throw ToolError("select failed"); "select #$id" }
                "set_text", "clear" -> {
                    val t = up(n) { it.isEditable } ?: throw ToolError("node #$id is not a text field")
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (action == "clear") "" else text.orEmpty()) }
                    if (!t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ToolError("set_text failed: focus the field and use type")
                    "$action #$id"
                }
                else -> {
                    val a = scrollAction(action) ?: throw ToolError("unknown action: $action")
                    val t = up(n) { it.isScrollable } ?: throw ToolError("node #$id cannot scroll; use key or tap with x2,y2")
                    if (!t.performAction(a)) throw ToolError("$action failed: at the end, or the app does not take it; try key")
                    "$action #$id"
                }
            }
        }
    }

    private fun scrollAction(name: String): Int? = when (name) {
        "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "scroll_up" -> AccessibilityAction.ACTION_SCROLL_UP.id
        "scroll_down" -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        "scroll_left" -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        "scroll_right" -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        else -> null
    }

    private fun key(key: String, times: Int): ToolResult {
        val s = service()
        return settle(KEY_SETTLE_MS) {
            repeat(times) { i ->
                val ok = when (key) {
                    "back" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    "home" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    "ok" -> s.rawKey("select")
                    "voice" -> s.rawKey("siri")
                    "up", "down", "left", "right", "play_pause", "volume_up", "volume_down", "mute" -> s.rawKey(key)
                    else -> throw ToolError("unknown key: $key")
                }
                if (!ok) throw ToolError("key $key failed")
                if (i < times - 1) SystemClock.sleep(KEY_GAP_MS)
            }
            if (times > 1) "key $key x$times" else "key $key"
        }
    }

    private fun tap(x: Int, y: Int, x2: Int?, y2: Int?, ms: Int): ToolResult = settle {
        gesture(x.toFloat(), y.toFloat(), x2?.toFloat(), y2?.toFloat(), if (x2 != null && y2 != null) ms.coerceIn(50, 3000) else 60)
        if (x2 != null && y2 != null) "swipe $x,$y -> $x2,$y2" else "tap $x,$y"
    }

    private fun gesture(x: Float, y: Float, x2: Float?, y2: Float?, ms: Int) {
        val s = service()
        val path = Path().apply { moveTo(x, y); if (x2 != null && y2 != null) lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms.toLong())).build()
        val done = CountDownLatch(1)
        val ok = AtomicReference(false)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }
        if (!s.dispatchGesture(g, cb, main)) throw ToolError("gesture refused")
        done.await(ms + 2000L, TimeUnit.MILLISECONDS)
        if (!ok.get()) throw ToolError("gesture cancelled")
    }

    private fun type(text: String, submit: Boolean): ToolResult {
        val s = service()
        return settle {
            val ok = onMain { s.agentText(text, submit) }
            if (!ok) {
                // No input connection: set the text of the focused editable node.
                val f = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                    ?: throw ToolError("no text field is open: click or focus a field first")
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ToolError("type failed")
                if (submit) s.rawKey("select")
            }
            "typed ${text.length} chars${if (submit) " and submit" else ""}"
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val ref = AtomicReference<Result<T>>()
        val done = CountDownLatch(1)
        main.post { ref.set(runCatching(block)); done.countDown() }
        if (!done.await(3, TimeUnit.SECONDS)) throw ToolError("the TV is busy, try again")
        return ref.get().getOrThrow()
    }

    private fun wait(a: JSONObject): ToolResult {
        val timeout = (a.intOrNull("timeout_ms") ?: 5000).coerceIn(100, 30_000).toLong()
        val since = a.intOrNull("since")?.toLong()
        val w = when (val mode = a.str("for")) {
            "change" -> events.waitQuiet(since ?: events.version, needChange = true, quietMs = QUIET_MS, timeoutMs = timeout)
            "window" -> events.waitWindow(events.windowVersion, a.strOrNull("package"), timeout)
            "text" -> {
                val text = a.str("text")
                service()
                events.waitCheck(timeout) { runCatching { snapshot().nodes.any { UiTree.matches(it, text) } }.getOrDefault(false) }
            }
            else -> throw ToolError("unknown wait: $mode")
        }
        val pkg = RemoteAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: events.pkg
        return ToolResult("${if (w.ok) "ok" else "timeout"} after ${w.ms} ms; ${header(pkg)}")
    }

    // ---- Apps and intents ----

    private fun info(pkg: String, flags: Int = 0): PackageInfo = try {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } catch (_: PackageManager.NameNotFoundException) {
        throw ToolError("no package $pkg (or Telik cannot see it); see apps")
    }

    private fun apps(): ToolResult {
        val list = AppRepository(context).load().sortedBy { it.label.lowercase() }
        val lines = list.map { a ->
            val v = runCatching { info(a.packageName).versionName }.getOrNull()
            "${a.packageName} \"${a.label}\"${v?.let { " v$it" } ?: ""}"
        }
        return ToolResult("${lines.size} apps\n" + lines.joinToString("\n"))
    }

    private fun startChecked(intent: Intent): String {
        val r = pm.resolveActivity(intent, 0) ?: throw ToolError("no activity takes this intent")
        val target = "${r.activityInfo.packageName}/${r.activityInfo.name}"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The accessibility service is the start context: Android 14 blocks starts from a background service.
        runCatching { (RemoteAccessibilityService.instance ?: context).startActivity(intent) }
            .onFailure { throw ToolError("start failed for $target: ${it.javaClass.simpleName}: ${it.message?.take(160)}") }
        return target
    }

    private fun launch(pkg: String): ToolResult {
        info(pkg)
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: throw ToolError("$pkg has no launch activity")
        return settle { "launched ${startChecked(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))}" }
    }

    private companion object {
        const val MAX_VISIT = 4000
        const val QUIET_MS = 300L
        const val SETTLE_MS = 2000L
        const val KEY_SETTLE_MS = 800L
        const val KEY_GAP_MS = 120L
    }
}
