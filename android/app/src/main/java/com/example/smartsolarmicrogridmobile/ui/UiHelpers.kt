package com.example.smartsolarmicrogridmobile.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.smartsolarmicrogridmobile.R

// ---- Context extensions ----
fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

// ---- Drawable helpers ----
fun Context.roundedDrawable(fillColor: Int, strokeColor: Int? = null, strokeWidthDp: Int = 1, radiusDp: Int = 10): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fillColor)
        if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
    }
}

fun Context.buttonBackground(primary: Boolean): StateListDrawable {
    val fill = if (primary) color(R.color.solar_green) else color(R.color.solar_surface)
    val stroke = if (primary) null else color(R.color.solar_border)
    return StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), roundedDrawable(color(R.color.solar_disabled), stroke, radiusDp = 10))
        addState(intArrayOf(android.R.attr.state_pressed), roundedDrawable(if (primary) color(R.color.solar_green_hover) else color(R.color.solar_background), stroke, radiusDp = 10))
        addState(intArrayOf(), roundedDrawable(fill, stroke, radiusDp = 10))
    }
}

// ---- View builders ----
fun Context.statusPill(status: String): TextView {
    val (bg, fg) = when (status) {
        "PENDING" -> R.color.solar_status_pending_bg to R.color.solar_status_pending_fg
        "APPROVED" -> R.color.solar_status_approved_bg to R.color.solar_status_approved_fg
        "COMPLETED" -> R.color.solar_status_completed_bg to R.color.solar_status_completed_fg
        else -> R.color.solar_status_negative_bg to R.color.solar_status_negative_fg
    }
    return TextView(this).apply {
        text = status; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color(fg))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundedDrawable(color(bg), radiusDp = 20)
    }
}

fun LinearLayout.addStatusPill(status: String) {
    val pill = context.statusPill(status)
    addView(pill, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(8) })
}

fun LinearLayout.sectionTitle(text: String) {
    addView(TextView(context).apply {
        this.text = text; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(context.color(R.color.solar_body_text)); setPadding(0, context.dp(20), 0, context.dp(8))
    })
}

fun LinearLayout.fieldLabel(title: String) {
    addView(TextView(context).apply {
        text = title; textSize = 13f; setTextColor(context.color(R.color.solar_muted_green))
        typeface = Typeface.DEFAULT_BOLD; setPadding(context.dp(2), context.dp(4), 0, context.dp(6))
    })
}

fun LinearLayout.label(text: String, size: Float = 16f, color: Int = context.color(R.color.solar_body_text)): TextView {
    return TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color); setPadding(0, context.dp(6), 0, context.dp(10))
        addView(this)
    }
}

fun LinearLayout.button(title: String, primary: Boolean = true, action: () -> Unit): Button {
    return Button(context).apply {
        text = title; isAllCaps = false
        textSize = 15f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (primary) context.color(R.color.white) else context.color(R.color.solar_body_text))
        background = context.buttonBackground(primary)
        gravity = Gravity.CENTER
        stateListAnimator = null
        val vPad = context.dp(14)
        setPadding(context.dp(16), vPad, context.dp(16), vPad)
        setOnClickListener { action() }
        addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })
    }
}
