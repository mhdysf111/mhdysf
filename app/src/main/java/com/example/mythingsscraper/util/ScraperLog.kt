package com.example.mythingsscraper.util

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Minimal in-process pub/sub so the accessibility service (which has no
 * UI of its own) can show its progress in MainActivity's log view.
 * Keeps the last N lines so re-opening the activity mid-run still shows
 * recent history.
 */
object ScraperLog {
    private const val TAG = "MythingsScraper"
    private const val MAX_LINES = 500

    private val lines = ArrayDeque<String>()
    private var listener: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun setListener(l: ((String) -> Unit)?) {
        listener = l
    }

    @Synchronized
    fun history(): List<String> = lines.toList()

    @Synchronized
    fun log(message: String) {
        Log.d(TAG, message)
        lines.addLast(message)
        while (lines.size > MAX_LINES) lines.removeFirst()
        val l = listener
        if (l != null) {
            mainHandler.post { l(message) }
        }
    }
}
