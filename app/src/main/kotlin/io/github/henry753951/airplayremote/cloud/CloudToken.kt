package io.github.henry753951.airplayremote.cloud

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * The Cloudflare tunnel token (spec 012 FR-003). It lives in its own private file, apart from the settings that
 * the adb receiver lists. Telik never shows it and never logs it.
 */
object CloudToken {
    private const val FILE = "cloud"
    private const val KEY = "tunnel_token"

    fun get(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotEmpty() }

    /** Gives false for a value that is not a tunnel token. */
    fun set(context: Context, token: String): Boolean {
        val t = token.trim()
        if (!valid(t)) return false
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, t) }
        return true
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { remove(KEY) }
    }

    /** A tunnel token is base64 JSON with the account `a`, the tunnel id `t` and the secret `s`. */
    fun valid(token: String): Boolean {
        if (token.length !in 100..2000 || token.any { it.isWhitespace() }) return false
        return runCatching {
            val json = JSONObject(String(Base64.getDecoder().decode(token), Charsets.UTF_8))
            json.optString("a").isNotEmpty() && json.optString("t").isNotEmpty() && json.optString("s").isNotEmpty()
        }.getOrDefault(false)
    }

    /** The tunnel id of a token, for the settings. It is not secret: it is in the public DNS record. */
    fun tunnelId(token: String): String? = runCatching {
        JSONObject(String(Base64.getDecoder().decode(token), Charsets.UTF_8)).optString("t").takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * The one-time code of the setup page (spec 012 D6): 6 digits, valid for [TTL_MS] and for one token. Pure, with a
 * clock for unit tests.
 */
class SetupCode(private val clock: () -> Long = System::currentTimeMillis, private val random: SecureRandom = SecureRandom()) {
    private var code: String? = null
    private var until = 0L
    private var failures = 0

    @Synchronized
    fun current(): String {
        val now = clock()
        code?.takeIf { now < until }?.let { return it }
        return renew()
    }

    @Synchronized
    fun renew(): String {
        val c = (random.nextInt(900_000) + 100_000).toString()
        code = c
        until = clock() + TTL_MS
        failures = 0
        return c
    }

    /** True once for the correct code. Five wrong codes end the code. */
    @Synchronized
    fun use(given: String?): Boolean {
        val c = code ?: return false
        if (clock() >= until) { code = null; return false }
        if (given?.trim() != c) {
            if (++failures >= MAX_FAILURES) code = null
            return false
        }
        code = null
        return true
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        private const val MAX_FAILURES = 5
    }
}
