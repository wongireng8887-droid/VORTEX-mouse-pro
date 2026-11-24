package com.example.keymapper

interface TapSink {
    fun down(id: Int, x: Float, y: Float)
    fun up(id: Int)
}

/**
 * Ketuk berulang (turun 45 ms, naik 45 ms) untuk beberapa jari sekaligus dari SATU thread pewaktu.
 * Dijalankan di proses shell (InjectMain), jadi tidak membebani thread utama aplikasi
 * dan tidak ikut tertunda saat banyak tombol ditekan atau mouse look sedang jalan.
 *
 * Aturan (diuji): tiap id selalu bergantian turun/naik dimulai dari turun; stop(id) mengangkat jari
 * yang sedang turun lalu tidak ada kejadian lagi untuk id itu; start dua kali untuk id yang sama diabaikan;
 * stopAll mengangkat semua; thread pewaktu berhenti sendiri saat kosong dan hidup lagi saat dibutuhkan.
 */
class RepeatTapper(private val sink: TapSink, private val periodMs: Long = 45L) {
    private class State(val x: Float, val y: Float) { var isDown = false }

    private val lock = Any()
    private val states = LinkedHashMap<Int, State>()
    private var thread: Thread? = null

    val count: Int get() = synchronized(lock) { states.size }

    fun start(id: Int, x: Float, y: Float) {
        synchronized(lock) {
            if (states.containsKey(id)) return
            val st = State(x, y)
            states[id] = st
            st.isDown = true
            sink.down(id, x, y)
            ensureThread()
        }
    }

    fun stop(id: Int) {
        synchronized(lock) {
            val st = states.remove(id) ?: return
            if (st.isDown) sink.up(id)
        }
    }

    fun stopAll() {
        synchronized(lock) {
            for ((id, st) in states) if (st.isDown) sink.up(id)
            states.clear()
        }
    }

    // dipanggil di dalam lock
    private fun ensureThread() {
        if (thread != null) return
        val t = Thread {
            val periodNs = periodMs.coerceAtLeast(1L) * 1_000_000L
            var nextTick = System.nanoTime() + periodNs
            while (true) {
                try {
                    val waitNs = nextTick - System.nanoTime()
                    if (waitNs > 0L) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNs)
                } catch (e: InterruptedException) { return@Thread }

                var exit = false
                synchronized(lock) {
                    if (states.isEmpty()) {
                        thread = null
                        exit = true
                    } else {
                        // Sink hanya memasukkan perintah ke antrean Injector; interval dijaga
                        // memakai jam monoton agar keterlambatan kecil tidak terus menumpuk.
                        for ((id, st) in states) {
                            if (st.isDown) { sink.up(id); st.isDown = false }
                            else { sink.down(id, st.x, st.y); st.isDown = true }
                        }
                    }
                }
                if (exit) return@Thread

                nextTick += periodNs
                // Jangan mengejar tick yang terlewat secara bertubi-tubi; lanjutkan dari sekarang.
                val now = System.nanoTime()
                if (nextTick <= now) nextTick = now + periodNs
            }
        }
        t.isDaemon = true
        t.name = "VortexRepeat"
        thread = t
        t.start()
    }
}
