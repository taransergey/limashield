package com.limashield.log

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Полевая запись на диск (M5): события и сырой поток фиксов пишутся в files/field/
 * посуточными файлами, переживают перезапуски процесса и выгружаются zip-архивом.
 * Кольца EventLog/RawLog в памяти — оперативный срез; здесь — полная история дня.
 *
 * Вся файловая работа — на выделенном фоновом потоке (вызовы приходят с main).
 * Исключение — crash(): пишется синхронно, чтобы успеть до смерти процесса.
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

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "field-recorder").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "field").apply { mkdirs() }
        io.execute { cleanupOld() }
    }

    fun event(level: String, msg: String) {
        if (!enabled) return
        val ts = tsFmt.format(Date())
        io.execute { writeEvent(ts, level, msg) }
    }

    fun raw(line: String) {
        if (!enabled) return
        io.execute { writeRaw(line) }
    }

    fun flush() {
        io.execute { synchronized(this) { runCatching { rawWriter?.flush() } } }
    }

    /** Крашрепорт — синхронно и всегда, независимо от enabled. */
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

    /**
     * Собрать все полевые файлы (+extra) в zip. Выполняется в очереди записи
     * (сериализовано с write-операциями); блокирует вызывающий — звать с Dispatchers.IO.
     * Возвращает false, если писать нечего.
     */
    fun zipTo(target: File, extra: List<File> = emptyList()): Boolean = try {
        io.submit(Callable { doZip(target, extra) }).get(15, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    // ---- внутренности, только на потоке io (кроме crash) ----

    @Synchronized
    private fun writeEvent(ts: String, level: String, msg: String) {
        val d = dir ?: return
        runCatching {
            File(d, "events-${dayFmt.format(Date())}.log").appendText("$ts [$level] $msg\n")
        }
    }

    @Synchronized
    private fun writeRaw(line: String) {
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
    private fun doZip(target: File, extra: List<File>): Boolean {
        runCatching { rawWriter?.flush() }
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
