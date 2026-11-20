package com.example.keymapper

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

// kind: TAP (tap/tahan) atau UP/DOWN/LEFT/RIGHT (arah joystick)
data class Mapping(val keyCode: Int, val kind: String, val x: Float, val y: Float, val radius: Float = 150f)

fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

object Store {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("km", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Mapping> {
        val out = mutableListOf<Mapping>()
        try {
            val a = JSONArray(prefs(c).getString("maps", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Mapping(o.getInt("k"), o.getString("t"),
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.optDouble("r", 150.0).toFloat()))
            }
        } catch (e: Exception) {}
        return out
    }

    fun save(c: Context, list: List<Mapping>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("k", it.keyCode).put("t", it.kind)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("r", it.radius.toDouble()))
        }
        prefs(c).edit().putString("maps", a.toString()).apply()
    }

    fun games(c: Context): List<String> {
        return try {
            val a = JSONArray(prefs(c).getString("games", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveGames(c: Context, list: List<String>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        prefs(c).edit().putString("games", a.toString()).apply()
    }
}
