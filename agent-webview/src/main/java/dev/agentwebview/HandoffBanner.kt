package dev.agentwebview

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal status banner: shows that the agent is working, or that a person is needed with a
 * "Done" button. Plain views, no theme dependencies, so apps can restyle or replace it
 * (the session API is all they need: `resumeFromHuman()` and the event stream).
 */
class HandoffBanner(context: Context, private val onDone: () -> Unit) : LinearLayout(context) {
    private val label = TextView(context)
    private val done = Button(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        label.setTextColor(Color.WHITE)
        label.textSize = 14f
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        done.text = "Done"
        done.setOnClickListener { onDone() }
        addView(done, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = View.GONE
        // Taps on the banner must not reach the page underneath.
        isClickable = true
    }

    fun showStatus(text: String) {
        setBackgroundColor(Color.argb(220, 33, 33, 33))
        label.text = text
        done.visibility = View.GONE
        visibility = View.VISIBLE
    }

    fun showHandoff(reason: String) {
        setBackgroundColor(Color.argb(240, 183, 28, 28))
        label.text = "Needs you: $reason"
        done.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    fun hide() { visibility = View.GONE }
}
