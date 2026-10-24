package com.filemind.smartmanager

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Local-only crash log (nothing is sent anywhere). The last uncaught exception is saved to a private file and shown on the next
 * launch with a Share button, so a crash can be diagnosed without a computer. Chains to the previous handler (e.g. Crashlytics when enabled).
 */
object LocalCrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(
                    "FileMind ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} / ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${t.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.take(6000) }.getOrNull()
    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}
