package com.limashield.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ring buffer of events (spec §3): state transitions, criteria, errors. */
object EventLog {

    enum class Level { INFO, STATE, WARN, ERROR }

    data class Entry(val ts: Long, val level: Level, val msg: String)

    private const val CAPACITY = 500
    private const val TAG = "LimaShield"

    private val buf = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> get() = _entries

    @Synchronized
    fun log(level: Level, msg: String) {
        // Collapse per-second spam of identical events (e.g. "spoofing continues")
        val last = buf.lastOrNull()
        if (last != null && last.msg == msg && System.currentTimeMillis() - last.ts < 10_000) return
        buf.addLast(Entry(System.currentTimeMillis(), level, msg))
        while (buf.size > CAPACITY) buf.removeFirst()
        _entries.value = buf.toList()
        when (level) {
            Level.WARN -> Log.w(TAG, msg)
            Level.ERROR -> Log.e(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        FieldRecorder.event(level.name, msg)
    }

    @Synchronized
    fun dump(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return buf.joinToString("\n") { "${f.format(Date(it.ts))} [${it.level}] ${it.msg}" }
    }
}
