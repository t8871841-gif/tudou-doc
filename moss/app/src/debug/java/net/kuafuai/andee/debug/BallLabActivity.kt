package com.moss.app.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.moss.app.ui.ball.MossOrbView
import com.moss.app.ui.ball.OrbState

/**
 * One orb on a plain background, driven by intent extras, for `screencap`.
 *
 *   adb shell am start -n com.moss.app/.debug.BallLabActivity \
 *       --es state LISTENING --es bg light --ei dp 360
 *
 * Extras (all optional): `state` (an [OrbState] name), `bg` (`dark` | `light`
 * | `mid`), `dp` (the orb's side; 168 is the real folded size).
 *
 * Debug builds only — it lives in `src/debug`, so release never carries it.
 */
class BallLabActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var label: TextView
    private var ball: MossOrbView? = null
    private var builtDp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Long captures must not end on a dark screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(24, 40, 24, 0)
        }
        setContentView(root)
        apply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent)
    }

    private fun apply(i: Intent) {
        val bg = when (i.getStringExtra("bg")) {
            "light" -> 0xFFF4F6FB.toInt()
            "mid" -> 0xFF8A93A8.toInt()
            else -> 0xFF0F172A.toInt()
        }
        root.setBackgroundColor(bg)
        label.setTextColor(if (bg == 0xFFF4F6FB.toInt()) Color.DKGRAY else Color.LTGRAY)

        val dp = i.getIntExtra("dp", 360)
        if (ball == null || dp != builtDp) {
            ball?.let { root.removeView(it) }
            val px = (dp * resources.displayMetrics.density).toInt()
            ball = MossOrbView(this).also {
                root.addView(it, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
            }
            builtDp = dp
            root.removeView(label)
            root.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            ))
        }
        val state = runCatching { OrbState.valueOf(i.getStringExtra("state")?.uppercase() ?: "IDLE") }
            .getOrDefault(OrbState.IDLE)
        ball?.setOrbState(state)
        label.text = state.name
    }
}
