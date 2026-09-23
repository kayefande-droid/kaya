package com.kaya.booster

import android.content.Context
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash guard: uncaught exceptions are appended to a small ring file
 * (files/crash_log.txt, newest last, ~48 KB cap) so the Tuning screen can
 * show the user *why* something failed instead of the app just vanishing.
 * Also exposes [guard] for fire-and-forget native work: a failure there
 * logs and continues instead of taking the process down mid-match.
 */
object KayaGuard {

    private const val MAX_BYTES = 48 * 1024
    private const val MAX_FILE_BYTES = 40 * 1024
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { append(appContext, "CRASH", describe(thread, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Runs [block]; any throwable is logged, never rethrown. Returns null on failure. */
    fun <T> guard(tag: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        append(null, tag, describe(Thread.currentThread(), t))
        null
    }

    /** Runs [block] on a background thread; failures are logged, not fatal. */
    fun bg(tag: String, block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                append(null, tag, describe(Thread.currentThread(), t))
            }
        }.start()
    }

    /** Last ~40 log lines, for the Tuning screen. */
    fun tail(context: Context, lines: Int = 40): List<String> {
        val f = file(context) ?: return emptyList()
        return runCatching {
            f.readLines().takeLast(lines)
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context)?.delete() }
    }

    fun append(context: Context?, tag: String, message: String) {
        val ctx = context ?: appContext ?: return
        runCatching {
            val f = file(ctx) ?: return
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val keep = f.readLines().takeLast(60)
                f.writeText(keep.joinToString("\n"))
            }
            f.appendText("[${stamp.format(Date())}] $tag: ${message.take(1200)}\n")
        }
    }

    private var appContext: Context? = null

    fun attach(appContext: Context) {
        this.appContext = appContext.applicationContext
    }

    private fun file(context: Context): File? = runCatching {
        File(context.filesDir, "crash_log.txt")
    }.getOrNull()

    private fun describe(thread: Thread, t: Throwable): String =
        "${t.javaClass.simpleName} on ${thread.name}: ${t.message ?: "(no message)"}\n" +
            t.stackTrace.take(8).joinToString("\n") { "  at $it" }
}
