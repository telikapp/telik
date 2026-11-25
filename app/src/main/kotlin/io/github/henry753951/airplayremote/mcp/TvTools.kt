package io.github.henry753951.airplayremote.mcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import io.github.henry753951.airplayremote.headphones.LocalShell
import io.github.henry753951.airplayremote.headphones.ShellResult
import io.github.henry753951.airplayremote.launcher.AppRepository
import io.github.henry753951.airplayremote.launcher.NowPlayingListener
import io.github.henry753951.airplayremote.launcher.TvListings
import io.github.henry753951.airplayremote.remote.input.RemoteAccessibilityService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

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
        McpTool("app_info", "Package details: version, activities, deep links, search support, exported providers, permissions.",
            listOf("package" to "string")) { a -> appInfo(a.str("package")) },
        McpTool("launch", "Open an app by package.", listOf("package" to "string"), action = true) { a -> launch(a.str("package")) },
        McpTool("open", "Start an activity: a deep link (VIEW uri), an intent: uri from tv_rows, or an action with a package.",
            listOf("uri?" to "string:https://…, scheme://… or intent:…", "action?" to "string:default VIEW", "package?" to "string",
                "extras?" to "object:string extras"), action = true) { a -> open(a) },
        McpTool("search", "Search inside an app (ACTION_SEARCH, or play=true for MEDIA_PLAY_FROM_SEARCH). No package: list apps that accept it.",
            listOf("query?" to "string", "package?" to "string", "play?" to "boolean:play the first result"), action = true) { a -> search(a.strOrNull("query"), a.strOrNull("package"), a.boolOr("play", false)) },
        McpTool("media", "Now playing (no action) or control the media session.",
            listOf("action?" to "enum(play|pause|toggle|stop|next|previous|seek|skip):control", "ms?" to "integer:seek position",
                "seconds?" to "integer:skip by, minus to go back", "package?" to "string:session, default the active one"), action = true) { a -> media(a) },
        McpTool("tv_rows", "TV Provider rows of apps: watch_next, preview (home channels' programs) or channels, with the intent uri for open.",
            listOf("kind?" to "enum(watch_next|preview|channels):default watch_next", "package?" to "string", "max?" to "integer:default 20")) { a -> tvRows(a.strOrNull("kind") ?: "watch_next", a.strOrNull("package"), a.intOrNull("max") ?: 20) },
        McpTool("recipe", "Tested steps for an app (YouTube, KinoPub, Apple TV): find and play content, pause, seek, now playing.",
            listOf("package?" to "string:no package: list")) { a -> ToolResult(Recipes.text(a.strOrNull("package"))) },
        McpTool("debug", "adb data of the TV for debug: activity, window, media or logcat of a package. Needs network debugging.",
            listOf("kind" to "enum(activity|window|media|logcat)", "package?" to "string:for logcat", "lines?" to "integer:logcat lines, default 100")) { a -> debug(a.str("kind"), a.strOrNull("package"), (a.intOrNull("lines") ?: 100).coerceIn(10, 500)) },
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
        val result = when {
            w.ok -> "screen changed in ${w.ms} ms"
            w.version > before -> "screen still changing after ${w.ms} ms"
            else -> "no accessibility event"
        }
        ToolResult("$what; $result; ${header(pkg)}")
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
        // Home starts the launcher: it needs the full wait, as an app start.
        return settle(if (key == "home") SETTLE_MS else KEY_SETTLE_MS) {
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

    private fun appInfo(pkg: String): ToolResult {
        val p = info(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS)
        val sb = StringBuilder()
        val label = p.applicationInfo?.loadLabel(pm)?.toString()
        sb.append("$pkg \"$label\" v${p.versionName} (${p.longVersionCode}) targetSdk=${p.applicationInfo?.targetSdkVersion}\n")
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        launch?.component?.let { sb.append("launch: ${it.flattenToShortString()}\n") }
        val manifest = runCatching {
            ZipFile(p.applicationInfo!!.sourceDir).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        }.mapCatching { AxmlParser.parse(it) }.getOrNull()
        if (manifest != null) {
            val comps = ManifestSummary.components(manifest)
            val links = ManifestSummary.deepLinks(comps)
            sb.append("deep links: ").append(if (links.isEmpty()) "none" else links.joinToString(" ")).append('\n')
            for (c in comps.filter { it.kind == "activity" && it.exported && it.filters.isNotEmpty() }) {
                val acts = c.filters.flatMap { it.actions }.distinct().joinToString(",") { it.removePrefix("android.intent.action.") }
                sb.append("activity ${c.name.removePrefix(pkg)}: $acts${if (c.searchable) " searchable" else ""}\n")
            }
            comps.filter { it.kind == "provider" && it.exported }.forEach { sb.append("provider ${it.name.removePrefix(pkg)} authorities=${it.extra}\n") }
        } else {
            sb.append("deep links: manifest not readable\n")
        }
        val search = pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH).setPackage(pkg), 0).isNotEmpty()
        val play = pm.queryIntentActivities(Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(pkg), 0).isNotEmpty()
        val assist = pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(pkg), 0).isNotEmpty()
        sb.append("search: ${if (search) "SEARCH " else ""}${if (play) "MEDIA_PLAY_FROM_SEARCH " else ""}${if (assist) "ASSIST" else ""}".trimEnd().let { if (it == "search:") "search: none" else it }).append('\n')
        val perms = p.requestedPermissions.orEmpty().map { it.removePrefix("android.permission.") }
        sb.append("permissions (${perms.size}): ").append(perms.take(40).joinToString(",")).append(if (perms.size > 40) ",…" else "")
        if (Recipes.has(pkg)) sb.append("\nrecipe: call recipe package=$pkg")
        return ToolResult(sb.toString())
    }

    private fun startChecked(intent: Intent): String {
        val r = pm.resolveActivity(intent, 0) ?: throw ToolError("no activity takes this intent")
        val target = "${r.activityInfo.packageName}/${r.activityInfo.name}"
        // Explicit: a filter without CATEGORY_DEFAULT (SEARCH of KinoPub) takes only an explicit intent.
        intent.setClassName(r.activityInfo.packageName, r.activityInfo.name)
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

    private fun open(a: JSONObject): ToolResult {
        val uri = a.strOrNull("uri")
        val intent = if (uri != null && uri.startsWith("intent:")) {
            runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrElse { throw ToolError("bad intent uri") }
                .apply { selector = null }
        } else {
            Intent(a.strOrNull("action")?.let(::fullAction) ?: Intent.ACTION_VIEW).apply { uri?.let { data = Uri.parse(it) } }
        }
        a.strOrNull("package")?.let { intent.setPackage(it) }
        a.optJSONObject("extras")?.let { e -> e.keys().forEach { k -> intent.putExtra(k, e.optString(k)) } }
        if (intent.action in BLOCKED) throw ToolError("action not allowed")
        intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
        return settle { "opened ${startChecked(intent)}" }
    }

    private fun fullAction(a: String) = if (a.contains('.')) a else "android.intent.action.$a"

    private fun search(query: String?, pkg: String?, play: Boolean): ToolResult {
        val action = if (play) "android.media.action.MEDIA_PLAY_FROM_SEARCH" else Intent.ACTION_SEARCH
        if (pkg == null || query == null) {
            val list = (pm.queryIntentActivities(Intent(Intent.ACTION_SEARCH), PackageManager.MATCH_ALL).map { it.activityInfo.packageName + " SEARCH" } +
                pm.queryIntentActivities(Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH"), PackageManager.MATCH_ALL).map { it.activityInfo.packageName + " PLAY" })
                .distinct().sorted()
            return ToolResult("apps that take a search intent (give query and package):\n" + list.joinToString("\n"))
        }
        val intent = Intent(action).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        return settle { "search in ${startChecked(intent)}" }
    }

    // ---- Media ----

    private fun sessions(): List<MediaController> = try {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, NowPlayingListener::class.java))
    } catch (_: SecurityException) {
        throw ToolError("Telik has no notification access: allow it in Settings > Apps > Special access > Notification access")
    }

    private fun position(s: PlaybackState): Long {
        val since = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return (s.position + if (s.state == PlaybackState.STATE_PLAYING) (since * s.playbackSpeed).toLong() else 0L).coerceAtLeast(0)
    }

    private fun describe(c: MediaController): String {
        val s = c.playbackState
        val m = c.metadata
        val sb = StringBuilder(c.packageName)
        sb.append(' ').append(stateName(s?.state))
        m?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let { sb.append(" \"").append(it).append('"') }
        (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))?.let { sb.append(" by \"").append(it).append('"') }
        if (s != null) sb.append(" pos=").append(time(position(s)))
        m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.let { sb.append(" len=").append(time(it)) }
        s?.let { sb.append(" can=").append(actions(it.actions)) }
        return sb.toString()
    }

    private fun time(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun stateName(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING -> "playing"; PlaybackState.STATE_PAUSED -> "paused"; PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_STOPPED -> "stopped"; PlaybackState.STATE_ERROR -> "error"; PlaybackState.STATE_NONE, null -> "none"; else -> "state$s"
    }

    private fun actions(a: Long): String = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "toggle",
        PlaybackState.ACTION_STOP to "stop", PlaybackState.ACTION_SEEK_TO to "seek", PlaybackState.ACTION_SKIP_TO_NEXT to "next",
        PlaybackState.ACTION_SKIP_TO_PREVIOUS to "previous", PlaybackState.ACTION_FAST_FORWARD to "ff", PlaybackState.ACTION_REWIND to "rew",
    ).filter { a and it.first != 0L }.joinToString(",") { it.second }

    private fun playing(c: MediaController) = c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    /**
     * Some apps (KinoPub) take the transport controls but do not act on them. Then Telik sends the media key to
     * the session, and at last the play/pause key of the remote.
     */
    private fun confirm(c: MediaController, want: Boolean) {
        fun ok(): Boolean { SystemClock.sleep(MEDIA_SETTLE_MS); return playing(c) == want }
        if (ok()) return
        val code = if (want) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        if (ok()) return
        service().rawKey("play_pause")
        if (!ok()) throw ToolError("the app did not change the playback state")
    }

    private fun media(a: JSONObject): ToolResult {
        val list = sessions()
        val action = a.strOrNull("action")
        if (action == null) {
            if (list.isEmpty()) return ToolResult("no media sessions")
            return ToolResult(list.joinToString("\n") { describe(it) })
        }
        val pkg = a.strOrNull("package")
        val c = (if (pkg != null) list.firstOrNull { it.packageName == pkg } else list.firstOrNull {
            it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)
        }) ?: throw ToolError("no active media session${pkg?.let { " of $it" } ?: ""}")
        val t = c.transportControls
        return settle {
            when (action) {
                "play" -> if (!playing(c)) { t.play(); confirm(c, want = true) }
                "pause" -> if (playing(c)) { t.pause(); confirm(c, want = false) }
                "toggle" -> { val want = !playing(c); if (want) t.play() else t.pause(); confirm(c, want) }
                "stop" -> t.stop()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "seek" -> t.seekTo((a.intOrNull("ms") ?: throw ToolError("missing ms")).toLong().coerceAtLeast(0))
                "skip" -> {
                    val s = c.playbackState ?: throw ToolError("no playback state")
                    val by = (a.intOrNull("seconds") ?: throw ToolError("missing seconds")) * 1000L
                    val target = (position(s) + by).coerceAtLeast(0)
                    t.seekTo(target)
                    SystemClock.sleep(MEDIA_SETTLE_MS)
                    val now = c.playbackState?.let(::position) ?: 0L
                    if (kotlin.math.abs(now - target) > 5_000) throw ToolError("the app did not seek (pos=${time(now)}); use key left/right in its player")
                }
                else -> throw ToolError("unknown media action: $action")
            }
            "media $action: ${describe(c)}"
        }
    }

    // ---- TV Provider ----

    private fun tvRows(kind: String, pkg: String?, max: Int): ToolResult {
        if (!TvListings.granted(context)) throw ToolError("Telik has no TV listings permission (READ_TV_LISTINGS)")
        val (uri, cols) = when (kind) {
            "watch_next" -> TvContract.WatchNextPrograms.CONTENT_URI to arrayOf("package_name", "title", "episode_title", "last_playback_position_millis", "duration_millis", "intent_uri", "watch_next_type")
            "preview" -> TvContract.PreviewPrograms.CONTENT_URI to arrayOf("package_name", "title", "episode_title", "last_playback_position_millis", "duration_millis", "intent_uri", "channel_id")
            "channels" -> TvContract.Channels.CONTENT_URI to arrayOf("package_name", "display_name", "description", "type", "app_link_intent_uri", "browsable", "_id")
            else -> throw ToolError("unknown kind: $kind")
        }
        val lines = ArrayList<String>()
        var total = 0
        try {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0) ?: continue
                    if (pkg != null && p != pkg) continue
                    total++
                    if (lines.size >= max.coerceIn(1, 200)) continue
                    fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
                    lines += if (kind == "channels") {
                        "$p \"${s(1)}\" id=${s(6)} type=${s(3)} browsable=${s(5)}${s(4)?.let { " link=$it" } ?: ""}"
                    } else {
                        val pos = if (c.isNull(3)) 0 else c.getLong(3)
                        val len = if (c.isNull(4)) 0 else c.getLong(4)
                        "$p \"${s(1)}\"${s(2)?.let { " · \"$it\"" } ?: ""}${if (pos > 0) " pos=${time(pos)}" else ""}${if (len > 0) " len=${time(len)}" else ""}${s(6)?.let { if (kind == "preview") " channel=$it" else " type=$it" } ?: ""}${s(5)?.let { " uri=$it" } ?: ""}"
                    }
                }
            }
        } catch (e: RuntimeException) {
            throw ToolError("TV Provider query failed: ${e.javaClass.simpleName}")
        }
        if (total == 0) return ToolResult("no $kind rows${pkg?.let { " for $it" } ?: ""} (apps give only rows that are browsable or searchable to a launcher)")
        return ToolResult("$total $kind rows${if (total > lines.size) ", ${lines.size} shown" else ""}\n" + lines.joinToString("\n"))
    }

    // ---- Debug over the local adb port ----

    private fun debug(kind: String, pkg: String?, lines: Int): ToolResult {
        val cmd = when (kind) {
            "activity" -> "dumpsys activity activities | grep -E 'ResumedActivity|\\* Task\\{|\\* ActivityRecord|Hist #' | head -n 80"
            "window" -> "dumpsys window windows | grep -E 'Window #|mCurrentFocus|mFocusedApp|mViewVisibility|isOnScreen' | head -n 120"
            "media" -> "dumpsys media_session | head -n 160"
            "logcat" -> {
                val p = pkg?.takeIf { PACKAGE.matches(it) } ?: throw ToolError("logcat needs a package")
                "pid=\$(pidof $p); if [ -z \"\$pid\" ]; then echo 'not running: $p'; else logcat -d -t $lines --pid=\$pid; fi"
            }
            else -> throw ToolError("unknown kind: $kind")
        }
        return when (val r = LocalShell(context).run(cmd)) {
            is ShellResult.Done -> ToolResult(r.output.takeLast(MAX_DEBUG).ifEmpty { "(empty, exit ${r.exitCode})" })
            ShellResult.NoDebugging -> ToolResult.error("network debugging is off on the TV (Developer options > Network debugging)")
            ShellResult.NeedsApproval -> ToolResult.error("approve the Telik adb key on the TV, then try again")
        }
    }

    private companion object {
        const val MAX_VISIT = 4000
        const val QUIET_MS = 300L
        const val SETTLE_MS = 2000L
        const val KEY_SETTLE_MS = 800L
        const val KEY_GAP_MS = 120L
        const val MEDIA_SETTLE_MS = 400L
        const val MAX_DEBUG = 16_000
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val BLOCKED = setOf(
            Intent.ACTION_DELETE, "android.intent.action.UNINSTALL_PACKAGE", "android.intent.action.INSTALL_PACKAGE",
            "android.intent.action.FACTORY_RESET", "android.intent.action.MASTER_CLEAR", Intent.ACTION_CALL,
        )
    }
}
