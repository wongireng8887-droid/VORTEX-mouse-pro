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
    val radius: Float = 150f, val mode: String = "HOLD", val sens: Float = 1f,
    val trig: Int = 0, val sensY: Float = 1f
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

    private const val DEFAULT_PROFILE = "Default"

    // ---------- profil keymap per game ----------
    // Game aktif = game yang terakhir dimulai dari aplikasi. Tiap game punya daftar profil;
    // tiap profil punya pemetaannya sendiri. Game yang belum punya data memakai pemetaan umum lama sebagai awal.
    fun currentGame(c: Context): String = prefs(c).getString("cur_game", "") ?: ""

    fun setCurrentGame(c: Context, pkg: String) {
        prefs(c).edit().putString("cur_game", pkg).apply()
    }

    private fun mapsKey(game: String, profile: String): String =
        if (game.isEmpty()) "maps" else "maps:$game:$profile"

    fun profiles(c: Context, game: String = currentGame(c)): List<String> {
        if (game.isEmpty()) return listOf(DEFAULT_PROFILE)
        return try {
            val a = JSONArray(prefs(c).getString("profs:$game", "[]"))
            val l = (0 until a.length()).map { a.getString(it) }
            if (l.isEmpty()) listOf(DEFAULT_PROFILE) else l
        } catch (e: Exception) { listOf(DEFAULT_PROFILE) }
    }

    fun activeProfile(c: Context, game: String = currentGame(c)): String {
        val names = profiles(c, game)
        val a = prefs(c).getString("active:$game", null)
        return if (a != null && a in names) a else names[0]
    }

    fun setActiveProfile(c: Context, game: String, name: String) {
        if (game.isEmpty() || name !in profiles(c, game)) return
        prefs(c).edit().putString("active:$game", name).apply()
    }

    private fun validName(c: Context, game: String, name: String, except: String? = null): Boolean {
        if (game.isEmpty() || name.isEmpty() || name.length > 24) return false
        return profiles(c, game).none { it.equals(name, true) && !it.equals(except, true) }
    }

    fun createProfile(c: Context, game: String, name: String): Boolean {
        val n = name.trim()
        if (!validName(c, game, n)) return false
        val names = profiles(c, game)
        val cur = activeProfile(c, game)
        val src = loadFor(c, game)
        val e = prefs(c).edit()
        // simpan dulu profil aktif apa adanya, lalu profil baru = salinan profil aktif
        if (!prefs(c).contains(mapsKey(game, cur))) e.putString(mapsKey(game, cur), toJson(src))
        e.putString(mapsKey(game, n), toJson(src))
        e.putString("profs:$game", JSONArray(names + n).toString())
        e.putString("active:$game", n)
        e.apply()
        return true
    }

    fun renameProfile(c: Context, game: String, old: String, name: String): Boolean {
        val n = name.trim()
        if (!validName(c, game, n, old)) return false
        val names = profiles(c, game)
        if (old !in names) return false
        val data = prefs(c).getString(mapsKey(game, old), null) ?: toJson(loadFor(c, game))
        val e = prefs(c).edit()
        e.remove(mapsKey(game, old))
        e.putString(mapsKey(game, n), data)
        e.putString("profs:$game", JSONArray(names.map { if (it == old) n else it }).toString())
        if (activeProfile(c, game) == old) e.putString("active:$game", n)
        e.apply()
        return true
    }

    fun deleteProfile(c: Context, game: String, name: String): Boolean {
        val names = profiles(c, game)
        if (game.isEmpty() || names.size <= 1 || name !in names) return false
        val rest = names - name
        val e = prefs(c).edit()
        e.remove(mapsKey(game, name))
        e.putString("profs:$game", JSONArray(rest).toString())
        if (activeProfile(c, game) == name) e.putString("active:$game", rest[0])
        e.apply()
        return true
    }

    private fun read(c: Context, key: String): MutableList<Mapping> {
        val out = mutableListOf<Mapping>()
        try {
            val a = JSONArray(prefs(c).getString(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Mapping(o.getInt("k"), o.getString("t"),
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.optDouble("r", 150.0).toFloat(), o.optString("m", "HOLD"),
                    o.optDouble("s", 1.0).toFloat(), o.optInt("g", 0),
                    o.optDouble("sy", o.optDouble("s", 1.0)).toFloat()))
            }
        } catch (e: Exception) {}
        return out
    }

    private fun toJson(list: List<Mapping>): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("k", it.keyCode).put("t", it.kind)
                .put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("r", it.radius.toDouble())
                .put("m", it.mode).put("s", it.sens.toDouble()).put("g", it.trig)
                .put("sy", it.sensY.toDouble()))
        }
        return a.toString()
    }

    private fun loadFor(c: Context, game: String): MutableList<Mapping> {
        if (game.isEmpty()) return read(c, "maps")
        val key = mapsKey(game, activeProfile(c, game))
        return if (prefs(c).contains(key)) read(c, key) else read(c, "maps")
    }

    // load/save selalu bekerja pada profil aktif milik game aktif
    fun load(c: Context): MutableList<Mapping> = loadFor(c, currentGame(c))

    fun save(c: Context, list: List<Mapping>) {
        val g = currentGame(c)
        prefs(c).edit().putString(mapsKey(g, if (g.isEmpty()) DEFAULT_PROFILE else activeProfile(c, g)), toJson(list)).apply()
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
