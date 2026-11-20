package com.example.keymapper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var pendingCode = 0
    private var pendingKind = "TAP"

    private val kinds = arrayOf("TAP", "UP", "DOWN", "LEFT", "RIGHT")
    private val labels = arrayOf("Tap / tahan", "Joystick ↑", "Joystick ↓", "Joystick ←", "Joystick →")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        root.addView(TextView(this).apply { text = "KeyMapper"; textSize = 24f })
        status = TextView(this); root.addView(status)
        root.addView(btn("1. Aktifkan service (Aksesibilitas)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. + Tambah mapping") { addFlow() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(TextView(this).apply { text = "\nF8 = nyalakan/matikan mapping" })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun serviceOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    private fun render() {
        status.text = if (serviceOn()) "Service: AKTIF" else "Service: belum aktif"
        list.removeAllViews()
        val maps = Store.load(this)
        for (m in maps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = labels[kinds.indexOf(m.kind).coerceAtLeast(0)]
            row.addView(TextView(this).apply {
                text = "${KeyEvent.keyCodeToString(m.keyCode).removePrefix("KEYCODE_")} → $name (${m.x.toInt()},${m.y.toInt()})"
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Hapus"
                setOnClickListener { Store.save(this@MainActivity, maps.filter { it !== m }); render() }
            })
            list.addView(row)
        }
    }

    private fun addFlow() {
        val dlg = AlertDialog.Builder(this).setTitle("Tekan tombol di keyboard…")
            .setNegativeButton("Batal", null).create()
        dlg.setOnKeyListener { d, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && code != KeyEvent.KEYCODE_BACK) {
                d.dismiss(); chooseKind(code); true
            } else false
        }
        dlg.show()
    }

    private fun chooseKind(code: Int) {
        AlertDialog.Builder(this).setTitle("Fungsi tombol ini?")
            .setItems(labels) { _, i ->
                pendingCode = code; pendingKind = kinds[i]
                val existing = Store.load(this).firstOrNull { it.kind != "TAP" }
                if (pendingKind != "TAP" && existing != null) {
                    // pusat joystick sudah ada, pakai ulang
                    save(Mapping(code, pendingKind, existing.x, existing.y, existing.radius))
                } else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(this, PickActivity::class.java), 1)
                }
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data != null) {
            save(Mapping(pendingCode, pendingKind, data.getFloatExtra("x", 0f), data.getFloatExtra("y", 0f)))
        }
    }

    private fun save(m: Mapping) {
        val l = Store.load(this).filter { it.keyCode != m.keyCode }.toMutableList()
        l.add(m); Store.save(this, l); render()
    }
}
