package com.example.mythingsscraper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.mythingsscraper.model.ScrapedItem
import com.example.mythingsscraper.util.CardExtractor
import com.example.mythingsscraper.util.CsvExporter
import com.example.mythingsscraper.util.MerchantQueue
import com.example.mythingsscraper.util.ScraperLog
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives the Mythings app's own screens (search -> store page -> scroll
 * catalog) and reads back what's rendered. Everything here is read-only
 * against the catalog: the only taps this makes inside Mythings are on
 * the search box, a matched store row, and category tabs - it never taps
 * a product card or its "+" button, so it can't add anything to a cart
 * or open an individual product page.
 *
 * All the real work happens on a background thread (runnerThread) so
 * Thread.sleep()-based "wait for the screen to settle" pacing doesn't
 * block the main thread. AccessibilityNodeInfo calls are safe to make
 * off the main thread.
 */
class ScraperAccessibilityService : AccessibilityService() {

    companion object {
        // TODO: replace with the real package name once you've found it with:
        //   adb shell pm list packages | grep -i mything
        // (must match app/src/main/AndroidManifest.xml <queries> and
        // res/xml/accessibility_service_config.xml too)
        private const val MYTHINGS_PACKAGE = "com.roxiit.mythings"

        private const val MAX_SCROLL_ATTEMPTS = 25
        private const val MAX_TAB_ATTEMPTS = 15
        private const val SHORT_WAIT_MS = 500L
        private const val SCREEN_SETTLE_MS = 1200L

        @Volatile
        var instance: ScraperAccessibilityService? = null
            private set
    }

    private var runnerThread: Thread? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        ScraperLog.log("Accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally unused: the scraping flow below polls
        // rootInActiveWindow on a worker thread instead of reacting to
        // individual events, which keeps the control flow simple and
        // sequential (search -> tap -> scrape -> next) rather than a
        // scattered callback state machine.
    }

    override fun onInterrupt() {
        ScraperLog.log("Accessibility service interrupted by the system.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun startProcessingQueue() {
        if (runnerThread?.isAlive == true) {
            ScraperLog.log("Already running - ignoring second start request.")
            return
        }
        runnerThread = thread(name = "mythings-scraper-runner") {
            try {
                runQueue()
            } catch (t: Throwable) {
                ScraperLog.log("FATAL: scraping run crashed: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------
    // Top-level flow
    // ---------------------------------------------------------------

    private fun runQueue() {
        ScraperLog.log("=== Run started ===")
        launchMythings()
        sleep(SCREEN_SETTLE_MS)

        while (!MerchantQueue.isEmpty()) {
            val merchant = MerchantQueue.takeNext() ?: break
            ScraperLog.log("--- Processing '$merchant' ---")
            try {
                processOneMerchant(merchant)
            } catch (t: Throwable) {
                ScraperLog.log("ERROR while processing '$merchant': ${t.message}")
            }
        }
        ScraperLog.log("=== Run finished. All merchants processed. ===")
    }

    private fun processOneMerchant(merchant: String) {
        if (!ensureOnSearchScreen()) {
            ScraperLog.log("Could not get back to the search screen - skipping '$merchant'.")
            return
        }

        if (!typeSearchQuery(merchant)) {
            ScraperLog.log("Could not find/type into the search box - skipping '$merchant'.")
            return
        }
        sleep(SCREEN_SETTLE_MS)

        val found = findAndOpenMerchant(merchant)
        if (found == null) {
            ScraperLog.log("'$merchant' not found in search results after scrolling/switching tabs.")
            return
        }
        sleep(SCREEN_SETTLE_MS)

        scrapeStorePage(merchant, found.second)
    }

    // ---------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------

    private fun launchMythings() {
        val launchIntent = packageManager.getLaunchIntentForPackage(MYTHINGS_PACKAGE)
        if (launchIntent == null) {
            ScraperLog.log("ERROR: could not resolve a launch intent for package '$MYTHINGS_PACKAGE'. " +
                "Is the package name correct and is the app installed?")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
    }

    /** Backs out of whatever screen we're on until the bottom nav (with a
     * "Search" tab) is visible, then taps it. Returns false if we can't
     * get there within a few attempts (e.g. app crashed, unexpected popup). */
    private fun ensureOnSearchScreen(): Boolean {
        repeat(6) { attempt ->
            val root = freshRoot() ?: return@repeat
            val searchTab = findNodeByExactText(root, "Search")
            if (searchTab != null) {
                clickNode(searchTab)
                sleep(SHORT_WAIT_MS)
                val afterRoot = freshRoot()
                if (afterRoot != null && findEditText(afterRoot) != null) {
                    return true
                }
            }
            if (attempt > 0) performGlobalAction(GLOBAL_ACTION_BACK)
            sleep(SHORT_WAIT_MS)
        }
        return false
    }

    private fun typeSearchQuery(query: String): Boolean {
        val root = freshRoot() ?: return false
        val editText = findEditText(root) ?: run {
            // Some screens require tapping a search icon/pill first to reveal the EditText.
            val searchHint = findNodeContainingText(root, "search", ignoreCase = true)
            if (searchHint != null) clickNode(searchHint)
            sleep(SHORT_WAIT_MS)
            findEditText(freshRoot() ?: return false)
        } ?: return false

        clickNode(editText)
        sleep(200)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        val setOk = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!setOk) return false

        sleep(400)
        val freshEditText = findEditText(freshRoot() ?: return true)
        freshEditText?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
        sleep(SHORT_WAIT_MS)
        return true
    }

    // ---------------------------------------------------------------
    // Search results: find the right row (handles "closed" shops lower
    // down the list, and shops filed under a different result tab)
    // ---------------------------------------------------------------

    /** Returns (merchantStatus, categoryTabTextWeEndedUpOn) once the store page is open, or null. */
    private fun findAndOpenMerchant(merchant: String): Pair<String, String>? {
        val visitedTabs = mutableSetOf<String>()

        repeat(8) { tabPass ->
            // Try the currently-visible result list first.
            val hit = scanAndTapMerchant(merchant)
            if (hit != null) return hit

            // Not found on this tab after exhausting scroll - try switching
            // to another result category tab (Food/Sweets/Groceries/Shops/...).
            val root = freshRoot() ?: return null
            val tabs = enumerateTabCandidates(root)
            val nextTab = tabs.firstOrNull { tabText ->
                tabText !in visitedTabs
            }
            if (nextTab == null) {
                ScraperLog.log("No more result tabs left to try for '$merchant'.")
                return null
            }
            visitedTabs.add(nextTab)
            val tabNode = findNodeByExactText(root, nextTab)
            if (tabNode != null) {
                ScraperLog.log("Trying result tab '$nextTab'...")
                clickNode(tabNode)
                sleep(SCREEN_SETTLE_MS)
            }
        }
        return null
    }

    /** Scrolls the current result list looking for an exact (then partial) name match. */
    private fun scanAndTapMerchant(merchant: String): Pair<String, String>? {
        var lastDumpSignature = ""
        repeat(MAX_SCROLL_ATTEMPTS) {
            val root = freshRoot() ?: return null

            val exact = findNodeByExactText(root, merchant, ignoreCase = true)
            val match = exact ?: findNodeContainingText(root, merchant, ignoreCase = true)
            if (match != null) {
                val rowContainer = findClickableAncestor(match) ?: match
                val status = detectMerchantStatus(rowContainer)
                ScraperLog.log("Found '$merchant' (status=$status). Opening store page...")
                clickNode(rowContainer)
                sleep(SCREEN_SETTLE_MS)
                val afterRoot = freshRoot()
                val category = afterRoot?.let { findSelectedTabText(it) } ?: ""
                return status to category
            }

            val scrollable = findMainScrollable(root)
            if (scrollable == null) {
                return null
            }
            val signature = dumpSignature(root)
            if (signature == lastDumpSignature) {
                return null // reached the end of the list, nothing matched
            }
            lastDumpSignature = signature
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            sleep(SHORT_WAIT_MS)
        }
        return null
    }

    private fun detectMerchantStatus(rowContainer: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo) {
            (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.let {
                if (it.isNotEmpty()) texts.add(it)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(rowContainer)
        return when {
            texts.any { it.equals("closed", ignoreCase = true) } -> "closed"
            texts.any { it.equals("open soon", ignoreCase = true) } -> "open_soon"
            else -> "open"
        }
    }

    // ---------------------------------------------------------------
    // Store page: iterate category tabs, scrape each one's cards
    // ---------------------------------------------------------------

    private fun scrapeStorePage(merchant: String, merchantStatus: String) {
        val visitedCategories = mutableSetOf<String>()
        val collected = LinkedHashMap<Pair<String, String>, ScrapedItem>() // (category, name+price) dedup key

        // Deliberately a while/break loop (not `repeat`) so every exit path
        // falls through to the single CsvExporter.append() call at the
        // bottom - no early `return` here that could skip saving what
        // we've already collected.
        var attempts = 0
        while (attempts < MAX_TAB_ATTEMPTS) {
            attempts++
            val root = freshRoot()
            if (root == null) {
                ScraperLog.log("Lost the window while scraping '$merchant' - stopping early.")
                break
            }

            val currentCategory = findSelectedTabText(root) ?: "default"
            if (currentCategory in visitedCategories) {
                val moved = clickNextUnvisitedTab(root, visitedCategories)
                if (!moved) break
                sleep(SCREEN_SETTLE_MS)
                continue
            }

            ScraperLog.log("Scraping category '$currentCategory' for '$merchant'...")
            val items = scrapeCurrentCategoryList(merchant, currentCategory, merchantStatus)
            for (item in items) {
                collected[currentCategory to (item.itemName + "|" + item.priceRaw)] = item
            }
            visitedCategories.add(currentCategory)
            ScraperLog.log("Category '$currentCategory' done: ${items.size} items.")

            val movedToNext = clickNextUnvisitedTab(root, visitedCategories)
            if (!movedToNext) {
                ScraperLog.log("No more unvisited category tabs for '$merchant'.")
                break
            }
            sleep(SCREEN_SETTLE_MS)
        }

        CsvExporter.append(this, collected.values.toList())
        ScraperLog.log("Saved ${collected.size} rows for '$merchant' to CSV.")
    }

    private fun clickNextUnvisitedTab(root: AccessibilityNodeInfo, visited: Set<String>): Boolean {
        var candidates = enumerateTabCandidates(root)
        var next = candidates.firstOrNull { it !in visited }
        if (next == null) {
            // Maybe more tabs are scrolled off-screen to the right.
            val tabStrip = findTabStrip(root)
            if (tabStrip != null) {
                tabStrip.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                sleep(SHORT_WAIT_MS)
                val refreshedRoot = freshRoot() ?: return false
                candidates = enumerateTabCandidates(refreshedRoot)
                next = candidates.firstOrNull { it !in visited }
            }
        }
        if (next == null) return false
        val tabNode = findNodeByExactText(root, next) ?: return false
        clickNode(tabNode)
        return true
    }

    /** Scrolls the product list for the currently-open category, collecting
     * (name, price, image) triples until scrolling stops revealing new content. */
    private fun scrapeCurrentCategoryList(
        merchant: String,
        category: String,
        merchantStatus: String
    ): List<ScrapedItem> {
        val results = LinkedHashMap<String, ScrapedItem>() // key = name+price
        var lastSignature = ""
        val now = Instant.now().toString()

        repeat(MAX_SCROLL_ATTEMPTS) {
            val root = freshRoot() ?: return results.values.toList()
            val listContainer = findMainScrollable(root) ?: return results.values.toList()

            var screenshotBitmap: Bitmap? = null // lazily captured only if a new card needs an image

            for (i in 0 until listContainer.childCount) {
                val child = listContainer.getChild(i) ?: continue
                val raw = CardExtractor.readCard(child)
                val extracted = CardExtractor.extract(raw) ?: continue
                val key = "${extracted.name}|${extracted.priceRaw}"
                if (results.containsKey(key)) continue

                var imagePath: String? = null
                if (extracted.imageBounds != null) {
                    if (screenshotBitmap == null) screenshotBitmap = takeScreenshotBitmap()
                    val bmp = screenshotBitmap
                    if (bmp != null) {
                        imagePath = cropAndSave(bmp, extracted.imageBounds, "${sanitize(merchant)}_${sanitize(category)}_${results.size}")
                    }
                }

                results[key] = ScrapedItem(
                    merchantName = merchant,
                    category = category,
                    itemName = extracted.name,
                    priceRaw = extracted.priceRaw,
                    imagePath = imagePath,
                    scrapedAtIso = now,
                    merchantStatus = merchantStatus
                )
            }

            val signature = dumpSignature(root)
            if (signature == lastSignature) {
                return results.values.toList() // end of this category's list
            }
            lastSignature = signature
            listContainer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            sleep(SHORT_WAIT_MS)
        }
        return results.values.toList()
    }

    // ---------------------------------------------------------------
    // Generic accessibility-tree helpers
    // ---------------------------------------------------------------

    private fun freshRoot(): AccessibilityNodeInfo? =
        // Deliberately NOT filtered to MYTHINGS_PACKAGE: ensureOnSearchScreen()
        // needs to inspect whatever's foreground (e.g. the launcher mid-transition)
        // while backing out of a deep screen.
        rootInActiveWindow

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (n.className?.toString()?.contains("EditText") == true) {
                found = n
                return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return found
    }

    private fun findNodeByExactText(root: AccessibilityNodeInfo, text: String, ignoreCase: Boolean = true): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()
            if (t != null && t.equals(text, ignoreCase = ignoreCase)) {
                found = n
                return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return found
    }

    private fun findNodeContainingText(root: AccessibilityNodeInfo, text: String, ignoreCase: Boolean = true): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString())
            if (t != null && t.contains(text, ignoreCase = ignoreCase)) {
                found = n
                return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return found
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo, maxLevels: Int = 8): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var levels = 0
        while (current != null && levels < maxLevels) {
            if (current.isClickable) return current
            current = current.parent
            levels++
        }
        return null
    }

    /** Largest-area scrollable node on screen - in practice this is the
     * main vertical list (search results, or a store's product list),
     * since tab strips and horizontal preview carousels are much smaller. */
    private fun findMainScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height().toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return best
    }

    /** The topmost scrollable node whose bounds differ from the main list's -
     * our heuristic for "the horizontal category tab strip". Compared by
     * on-screen rect rather than object identity, since AccessibilityNodeInfo
     * instances from repeated getChild() walks aren't reliably reference-equal. */
    private fun findTabStrip(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val main = findMainScrollable(root)
        val mainRect = Rect().also { main?.getBoundsInScreen(it) }
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (main == null || r != mainRect) {
                    if (r.top < bestTop) {
                        bestTop = r.top
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return best
    }

    /** Short, clickable text nodes that look like category tab labels.
     * IMPORTANT: scoped to the tab-strip container when we can find one
     * (via findTabStrip), and otherwise restricted to nodes strictly above
     * the main list's top edge. Without that scoping this would also pick
     * up clickable store names from a search-results list below the tabs
     * and risk tapping into the wrong store. */
    private fun enumerateTabCandidates(root: AccessibilityNodeInfo): List<String> {
        val tabStrip = findTabStrip(root)
        val found = mutableListOf<String>()

        if (tabStrip != null) {
            fun walkScoped(n: AccessibilityNodeInfo) {
                val t = n.text?.toString()?.trim()
                if (n.isClickable && !t.isNullOrEmpty() && t.length in 1..25 && !t.contains("\n")) {
                    found.add(t)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walkScoped(it) }
            }
            walkScoped(tabStrip)
            if (found.isNotEmpty()) return found.distinct()
        }

        // Fallback: no distinct tab-strip scrollable found (tabs might all
        // fit without scrolling). Only accept nodes above the main list's
        // top edge, so result/product rows below it are never mistaken for tabs.
        val main = findMainScrollable(root)
        val mainTop = if (main != null) Rect().also { main.getBoundsInScreen(it) }.top else Int.MAX_VALUE
        fun walkUnscoped(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()?.trim()
            if (n.isClickable && !t.isNullOrEmpty() && t.length in 1..25 && !t.contains("\n")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.top < mainTop) found.add(t)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walkUnscoped(it) }
        }
        walkUnscoped(root)
        return found.distinct()
    }

    private fun findSelectedTabText(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (found != null) return
            if (n.isSelected) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    found = t
                    return
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return found
    }

    /** Cheap way to tell "did the screen actually change after that scroll"
     * without doing a full diff: count of distinct text nodes + their
     * concatenated length. Good enough to detect "end of list reached". */
    private fun dumpSignature(root: AccessibilityNodeInfo): String {
        var count = 0
        var totalLen = 0
        fun walk(n: AccessibilityNodeInfo) {
            val t = n.text?.toString()
            if (!t.isNullOrEmpty()) {
                count++
                totalLen += t.length
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return "$count:$totalLen"
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val ancestor = if (node.isClickable) node else findClickableAncestor(node)
        val target = ancestor ?: node
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // Fallback: simulate a real tap at the node's on-screen center.
        val r = Rect()
        target.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                ok = false
                latch.countDown()
            }
        }, null)
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    // ---------------------------------------------------------------
    // Screenshot capture (for per-card product images only - API 30+)
    // ---------------------------------------------------------------

    private fun takeScreenshotBitmap(): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hb = screenshot.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                    result = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                } catch (t: Throwable) {
                    ScraperLog.log("Screenshot processing failed: ${t.message}")
                } finally {
                    latch.countDown()
                }
            }
            override fun onFailure(errorCode: Int) {
                ScraperLog.log("takeScreenshot failed with code $errorCode")
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun cropAndSave(full: Bitmap, bounds: Rect, fileNamePrefix: String): String? {
        val safe = Rect(bounds)
        if (!safe.intersect(0, 0, full.width, full.height) || safe.width() <= 0 || safe.height() <= 0) {
            return null
        }
        return try {
            val cropped = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
            val dir = File(getExternalFilesDir(null), "images").apply { mkdirs() }
            val file = File(dir, "$fileNamePrefix.jpg")
            FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            file.absolutePath
        } catch (t: Throwable) {
            ScraperLog.log("Image crop/save failed: ${t.message}")
            null
        }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
