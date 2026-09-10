package com.limashield.log

/**
 * Кольцо сырых фиксов обоих провайдеров (CSV). Уходит в logcat (LimaShieldRaw)
 * и в шарящийся файл лога — полевые записи для разбора реальных срабатываний (M5).
 */
object RawLog {

    private const val CAPACITY = 1200 // ~15 минут GNSS @1 Гц + сеть

    private val buf = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        buf.addLast(line)
        while (buf.size > CAPACITY) buf.removeFirst()
    }

    @Synchronized
    fun dump(): String = buf.joinToString("\n")
}
