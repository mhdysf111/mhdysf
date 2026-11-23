package com.example.mythingsscraper.util

/**
 * Simple in-memory FIFO of merchant names to scrape. A process-wide
 * singleton so both MainActivity (which fills it) and the
 * AccessibilityService (which drains it) see the same queue.
 *
 * This is intentionally the only "batch" abstraction in the app: add one
 * merchant today, add fifty later - the scraping logic doesn't change.
 */
object MerchantQueue {
    private val pending = ArrayDeque<String>()

    @Volatile
    var currentlyProcessing: String? = null
        private set

    @Synchronized
    fun add(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) pending.addLast(trimmed)
    }

    @Synchronized
    fun snapshot(): List<String> = pending.toList()

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    @Synchronized
    fun takeNext(): String? {
        val next = pending.removeFirstOrNull()
        currentlyProcessing = next
        return next
    }

    @Synchronized
    fun clear() {
        pending.clear()
        currentlyProcessing = null
    }
}
