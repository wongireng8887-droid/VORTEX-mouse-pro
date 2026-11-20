package com.example.keymapper

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

class PickActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = TextView(this).apply {
            text = "Tap posisi tombol/joystick di layar"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER; textSize = 18f
        }
        @Suppress("DEPRECATION")
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                setResult(RESULT_OK, Intent().putExtra("x", e.rawX).putExtra("y", e.rawY))
                finish()
            }
            true
        }
        setContentView(v)
    }
}
