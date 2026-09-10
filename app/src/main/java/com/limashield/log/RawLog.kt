package com.limashield.log

/**
 * Ring of raw fixes from both providers (CSV). Goes to logcat (LimaShieldRaw)
 * and into the shareable log file — field recordings for analyzing real events (M5).
 */
object RawLog {

    private const val CAPACITY = 1200 // ~15 minutes of GNSS @1 Hz + network

    private val buf = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        buf.addLast(line)
        while (buf.size > CAPACITY) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")
}
