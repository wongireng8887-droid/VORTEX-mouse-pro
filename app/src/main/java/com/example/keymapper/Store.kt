package com.example.keymapper

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

const val MOUSE_LEFT = 10001
const val MOUSE_RIGHT = 10002
const val MOUSE_MID = 10003
const val LOOK_KEY = 20001

// kind: TAP (tombol), UP/DOWN/LEFT/RIGHT (arah joystick), LOOK (FPS Look / kamera mouse)
// mode (untuk TAP): HOLD, TAP, REPEAT, DOUBLE, RELEASE
data class Mapping(
    val keyCode: Int, val kind: String, val x: Float, val y: Float,
    val radius: Float = 150f, val mode: String = "HOLD", val sens: Float = 1f
)

fun keyName(code: Int): String = when (code) {
    MOUSE_LEFT -> "Left"
    MOUSE_RIGHT -> "Right"
    MOUSE_MID -> "Mid"
    LOOK_KEY -> "Look"
    0 -> "?"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

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
                    o.optDouble("r", 150.0).toFloat(), o.optString("m", "HOLD"),
                    o.optDouble("s", 1.0).toFloat()))
            }
        } catch (e: Exception) {}
        return out
    }

    fun save(c: Context, list: List<Mapping>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("k", it.keyCode).put("t", it.kind)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("r", it.radius.toDouble())
                .put("m", it.mode).put("s", it.sens.toDouble()))
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
