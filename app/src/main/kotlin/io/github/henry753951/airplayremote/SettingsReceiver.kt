package io.github.henry753951.airplayremote

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject

open class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_SET, InstallationContract.LEGACY_SET_SETTING -> _set(prefs, intent.getStringExtra(EXTRA_KEY), intent.getStringExtra(EXTRA_VALUE))
            ACTION_LIST, InstallationContract.LEGACY_LIST_SETTINGS -> _reply(true, _list(prefs))
            // Spec 011 FR-014: the token of the agent server from adb, so that it is not read off the screen.
            ACTION_SET_MCP_TOKEN -> {
                val ok = io.github.henry753951.airplayremote.mcp.McpToken.set(context, intent.getStringExtra(EXTRA_TOKEN).orEmpty())
                _reply(ok, if (ok) "mcp token set" else "invalid token: 20-128 of A-Z a-z 0-9 _ -")
            }
        }
    }

    private fun _set(prefs: SharedPreferences, key: String?, value: String?) {
        val pref = Prefs.all[key]
        when {
            pref == null -> _reply(false, "unknown key: $key")
            value == null || !pref.writeText(prefs, value) -> _reply(false, "invalid value for $key: $value")
            else -> _reply(true, "$key=$value")
        }
    }

    private fun _list(prefs: SharedPreferences) = JSONObject().apply {
        Prefs.all.forEach { (key, pref) -> put(key, JSONObject().put("value", prefs[pref]).put("default", pref.def)) }
    }.toString()

    private fun <T : Any> Pref<T>.writeText(p: SharedPreferences, s: String) = parse(s)?.let { write(p, it) } == true

    private fun _reply(ok: Boolean, data: String) =
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, data, null)

    private companion object {
        const val ACTION_SET = "io.github.henry753951.airplayremote.SET_SETTING"
        const val ACTION_LIST = "io.github.henry753951.airplayremote.LIST_SETTINGS"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val ACTION_SET_MCP_TOKEN = "io.github.henry753951.airplayremote.SET_MCP_TOKEN"
        const val EXTRA_TOKEN = "token"
    }
}
