package io.github.henry753951.airplayremote.mcp

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.henry753951.airplayremote.R

/**
 * Spec 012 D5: the TV asks before an agent gets access. "Allow" allows, Back or "Deny" denies. An overlay, not an
 * activity: Android 14 starts the activity of a background app behind the current app.
 */
object AgentApprove {
    private const val TAG = "AgentApprove"
    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context, p: OAuthServer.Pending) {
        val app = context.applicationContext
        main.post { showOnMain(app, p) }
    }

    private fun showOnMain(context: Context, p: OAuthServer.Pending) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "no overlay permission: the request waits and ends")
            return
        }
        val wm = context.getSystemService(WindowManager::class.java)
        val px = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }
        var answered = false
        lateinit var root: LinearLayout
        fun close() = runCatching { wm.removeView(root) }
        fun answer(allow: Boolean) {
            if (answered) return
            answered = true
            McpServer.oauth(context).answer(p.id, allow)
            close()
        }
        fun text(s: String, sp: Float, color: Long) = TextView(context).apply {
            text = s
            setTextColor(color.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER
            setPadding(0, px(6f), 0, px(6f))
        }
        fun button(s: String, allow: Boolean) = Button(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isFocusable = true
            setOnClickListener { answer(allow) }
        }
        root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) answer(false)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(40f), px(32f), px(40f), px(32f))
            background = GradientDrawable().apply { cornerRadius = px(24f).toFloat(); setColor(0xF21D1D20.toInt()) }
            addView(text(context.getString(R.string.agent_approve_title, p.clientName), 26f, 0xFFF5F5F7))
            addView(text(context.getString(R.string.agent_approve_desc), 18f, 0xFF9C9CA3))
            addView(text(p.display, 52f, 0xFFF5F5F7).apply { letterSpacing = 0.3f })
            val allow = button(context.getString(R.string.agent_approve_allow), true)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(allow)
                addView(View(context), LinearLayout.LayoutParams(px(24f), 1))
                addView(button(context.getString(R.string.agent_approve_deny), false))
            })
            post { allow.requestFocus() }
        }
        val params = WindowManager.LayoutParams(
            px(720f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.6f
        }
        runCatching { wm.addView(root, params) }.onFailure {
            Log.w(TAG, "overlay failed: ${it.javaClass.simpleName}")
            return
        }
        // The window closes with the request.
        main.postDelayed({ if (!answered) { answered = true; close() } }, OAuthServer.CONFIRM_MS)
    }
}
