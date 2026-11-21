package com.example.keymapper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

// Tes: apakah HP menerima keyboard dan mouse (gerak, klik).
class TestActivity : Activity() {
    private lateinit var tv: TextView
    private var key = "belum ada"
    private var move = "belum ada"
    private var click = "belum ada"
    private var tx = 0f
    private var ty = 0f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF0F1715.toInt()
        window.navigationBarColor = 0xFF0F1715.toInt()
        tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0F1715.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            addView(tv)
        }
        root.setOnCapturedPointerListener { _, e ->
            tx += e.x; ty += e.y
            if (e.actionMasked == MotionEvent.ACTION_MOVE) move = "OK (total ${tx.toInt()}, ${ty.toInt()})"
            val s = e.buttonState
            if (s != 0) {
                click = (if ((s and MotionEvent.BUTTON_PRIMARY) != 0) "kiri " else "") +
                    (if ((s and MotionEvent.BUTTON_SECONDARY) != 0) "kanan " else "") +
                    (if ((s and MotionEvent.BUTTON_TERTIARY) != 0) "tengah" else "")
                click = "OK ($click)"
            }
            render()
            true
        }
        root.setOnClickListener { root.requestPointerCapture() }
        setContentView(root)
        root.requestFocus()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) findViewById<View>(android.R.id.content).requestPointerCapture()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN) key = "OK (${keyName(e.keyCode)})"
        render()
        return true
    }

    private fun render() {
        tv.text = "TES KEYBOARD DAN MOUSE\n\n" +
            "Keyboard: $key\n" +
            "Gerak mouse: $move\n" +
            "Klik mouse: $click\n\n" +
            "Tekan tombol di keyboard, gerakkan dan klik mouse.\n" +
            "Kalau mouse tidak terbaca, ketuk layar sekali.\n" +
            "Tombol Kembali untuk keluar."
    }
}
