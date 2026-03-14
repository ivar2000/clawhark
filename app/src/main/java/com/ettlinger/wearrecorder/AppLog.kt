package com.ettlinger.wearrecorder

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Persistent file logger + logcat. Logs to /data/data/ai.etti.clawhark/files/logs/
 * Pull via: adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log"
 * Or: adb shell "run-as ai.etti.clawhark ls -la files/logs/"
 */
object AppLog {
    private const val TAG_PREFIX = "WR"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB, then rotate
    private const val FLUSH_BUFFER_SIZE = 4096
    private const val FLUSH_INTERVAL_MS = 30_000L
    private var logFile: File? = null
    private var logDir: File? = null
    private val buffer = StringBuilder()
    private var lastFlushTime = 0L
    // Safe to reuse: all access is @Synchronized
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (logFile != null) return // Already initialized
        val dir = File(context.filesDir, "logs")
        dir.mkdirs()
        logDir = dir
        logFile = File(dir, "clawhark.log")

        // Rotate if too big
        logFile?.let {
            if (it.exists() && it.length() > MAX_LOG_SIZE) {
                val old = File(dir, "clawhark.old.log")
                old.delete()
                it.renameTo(old)
                logFile = File(dir, "clawhark.log")
            }
        }

        i("AppLog", "=== LOG INIT === pid=${android.os.Process.myPid()} time=${formatDate()}")
    }

    fun d(tag: String, msg: String) {
        val fullTag = "$TAG_PREFIX.$tag"
        Log.d(fullTag, msg)
        writeToFile("D", fullTag, msg)
    }

    fun i(tag: String, msg: String) {
        val fullTag = "$TAG_PREFIX.$tag"
        Log.i(fullTag, msg)
        writeToFile("I", fullTag, msg)
    }

    fun w(tag: String, msg: String) {
        val fullTag = "$TAG_PREFIX.$tag"
        Log.w(fullTag, msg)
        writeToFile("W", fullTag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val fullTag = "$TAG_PREFIX.$tag"
        Log.e(fullTag, msg, throwable)
        val extra = throwable?.let { "\n  ${it.javaClass.simpleName}: ${it.message}\n  ${it.stackTraceToString().take(500)}" } ?: ""
        writeToFile("E", fullTag, "$msg$extra")
    }

    private fun formatDate(): String = dateFormat.format(Date())

    @Synchronized
    private fun writeToFile(level: String, tag: String, msg: String) {
        try {
            buffer.append(formatDate()).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            val now = System.currentTimeMillis()
            if (level == "E" || buffer.length >= FLUSH_BUFFER_SIZE || now - lastFlushTime >= FLUSH_INTERVAL_MS) {
                flushBuffer()
            }
        } catch (_: Exception) { /* don't crash on log failure */ }
    }

    @Synchronized
    fun flush() {
        flushBuffer()
    }

    // Must be called while holding the synchronized lock
    private fun flushBuffer() {
        if (buffer.isEmpty()) return
        try {
            logFile?.let { f ->
                FileWriter(f, true).use { w ->
                    w.write(buffer.toString())
                }
                // Rotate at runtime if log grew past limit
                if (f.exists() && f.length() > MAX_LOG_SIZE) {
                    logDir?.let { dir ->
                        val old = File(dir, "clawhark.old.log")
                        old.delete()
                        f.renameTo(old)
                        logFile = File(dir, "clawhark.log")
                    }
                }
            }
            buffer.clear()
            lastFlushTime = System.currentTimeMillis()
        } catch (_: Exception) { /* don't crash on log failure */ }
    }
}
