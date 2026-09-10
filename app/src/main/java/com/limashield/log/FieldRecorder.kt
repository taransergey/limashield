package com.limashield.log

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Полевая запись на диск (M5): события и сырой поток фиксов пишутся в files/field/
 * посуточными файлами, переживают перезапуски процесса и выгружаются zip-архивом.
 * Кольца EventLog/RawLog в памяти — оперативный срез; здесь — полная история дня.
 */
object FieldRecorder {

    @Volatile
    var enabled = true

    private const val KEEP_DAYS = 7L

    private var dir: File? = null
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var rawWriter: BufferedWriter? = null
    private var rawDay = ""
    private var rawCount = 0

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "field").apply { mkdirs() }
        cleanupOld()
    }

    /** События — редкие; пишем append с немедленным закрытием, чтобы ничего не терять при kill. */
    @Synchronized
    fun event(level: String, msg: String) {
        if (!enabled) return
        val d = dir ?: return
        runCatching {
            File(d, "events-${dayFmt.format(Date())}.log")
                .appendText("${tsFmt.format(Date())} [$level] $msg\n")
        }
    }

    /** Сырые фиксы — 1 Гц; буферизованная запись, flush каждые 30 строк. */
    @Synchronized
    fun raw(line: String) {
        if (!enabled) return
        val d = dir ?: return
        runCatching {
            val day = dayFmt.format(Date())
            if (day != rawDay || rawWriter == null) {
                rawWriter?.close()
                rawWriter = BufferedWriter(FileWriter(File(d, "raw-$day.csv"), true))
                rawDay = day
            }
            rawWriter!!.write(line)
            rawWriter!!.newLine()
            if (++rawCount % 30 == 0) rawWriter!!.flush()
        }
    }

    @Synchronized
    fun flush() {
        runCatching { rawWriter?.flush() }
    }

    /** Крашрепорт — пишется всегда, независимо от enabled. */
    @Synchronized
    fun crash(thread: Thread, e: Throwable) {
        val d = dir ?: return
        runCatching {
            File(d, "crash-${dayFmt.format(Date())}.txt")
                .appendText("${tsFmt.format(Date())} thread=${thread.name}\n${Log.getStackTraceString(e)}\n\n")
        }
    }

    fun files(): List<File> =
        dir?.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    /** Собрать все полевые файлы (+extra) в zip. Возвращает false, если писать нечего. */
    @Synchronized
    fun zipTo(target: File, extra: List<File> = emptyList()): Boolean {
        flush()
        val all = files() + extra.filter { it.isFile && it.length() > 0 }
        if (all.isEmpty()) return false
        runCatching {
            ZipOutputStream(target.outputStream().buffered()).use { zs ->
                for (f in all) {
                    zs.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zs) }
                    zs.closeEntry()
                }
            }
        }.onFailure { return false }
        return target.length() > 0
    }

    private fun cleanupOld() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        files().filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
    }
}
