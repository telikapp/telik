package io.github.henry753951.airplayremote.mcp

/**
 * Tested steps for the main apps of the TV check (spec 011 research.md). Short text, so that the agent spends few
 * tokens. Each line names the tool and the arguments.
 */
object Recipes {
    private val all = linkedMapOf(
        "com.google.android.youtube.tv" to """
            YouTube (com.google.android.youtube.tv). The UI gives no tree (Cobalt): do not walk menus, use links.
            search: open uri=https://www.youtube.com/results?search_query=<words> (results show; pick with key, or use a known id)
            video: open uri=https://www.youtube.com/watch?v=<id> (also youtu.be/<id>); it resumes at the saved position
            channel: open uri=https://www.youtube.com/@<handle> (a live preview may start: media action=pause)
            ids without UI: tv_rows kind=watch_next|preview package=com.google.android.youtube.tv give watch?v= links
            now playing: media (title and channel come 1-2 s after the start); pause/play: media action=pause|play
            seek: media action=skip seconds=<+-n> or action=seek ms=<pos>
            the app needs 5-10 s to load a link: wait for=window, then media
        """.trimIndent(),
        "watch.kino" to """
            KinoPub (watch.kino). Good tree: view ids (btnWatch, tvTitle, action_search), focus, text.
            launch resumes the last video in PlayerActivity: media action=pause at once, or key back
            title page: open uri=https://kino.pub/item/view/<id> package=watch.kino, then find Смотреть, act click
            ids: tv_rows kind=watch_next|preview package=watch.kino; open uri=tvrecommendation://app/playback/0/<id> resumes it
            search: find action_search, act click, type text=<words> submit=true, screen (cards with year and rating). The SEARCH intent shows no results
            now playing: media package=watch.kino (state, pos, len; no title). pause/play: media (falls back to the play/pause key)
            seek: the app ignores seek: in the player use key right/left
            player tree: only player_view; key ok shows the controls
        """.trimIndent(),
        "com.apple.atve.androidtv.appletv" to """
            Apple TV (com.apple.atve.androidtv.appletv). The UI gives no tree and no events: use links and media.
            title: open uri=https://tv.apple.com/<country>/<movie|show>/<slug>/<umc.cmc.id> (opens the title page)
            the id is in the address of the title on tv.apple.com in a browser
            search: open uri=https://tv.apple.com/search opens search, but ?term= is not filled
            play from the title page: key ok on the focused Play button; then media package=com.apple.atve.androidtv.appletv
            actions give "no accessibility event": that is normal here, check with media
        """.trimIndent(),
    )

    fun has(pkg: String) = pkg in all

    fun text(pkg: String?): String = when {
        pkg == null -> "recipes: " + all.keys.joinToString(", ")
        else -> all[pkg] ?: "no recipe for $pkg; use app_info for deep links and search support"
    }
}
