package com.example.mythingsscraper.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Turns one product-card subtree (one child of the catalog list, as seen
 * on a Mythings store page: item name, price like "50.00 JD", a photo,
 * and a "+" add-to-cart button) into a name/price/image-bounds triple.
 *
 * Built from the attached screenshots of a real store page:
 *   - Name and price are separate TextView nodes, name first in traversal
 *     order, e.g. "Royal Canin Dry Food Persian Cat 4 Kg" then "50.00" then "JD".
 *   - The amount and the currency code ("JD") are sometimes two separate
 *     text nodes and sometimes one ("50.00 JD") - both are handled.
 *   - There's always exactly one real product photo per card; the "+"
 *     button is a small icon, filtered out by minimum size.
 * This only ever reads text and bounds - it never calls performAction on
 * anything inside a card, so it can't accidentally tap "+" (add to cart)
 * or open the product's own page.
 */
object CardExtractor {

    private val CURRENCY_CODES = setOf(
        "JD", "JOD", "AED", "SAR", "KWD", "QAR", "BHD", "OMR", "EGP", "USD", "$"
    )

    // A bare amount like "50.00", "35.50", "1,500" - matched as a WHOLE
    // string, not a substring, so an item name containing a number
    // (e.g. "...4 Kg") never gets mistaken for a price.
    private val AMOUNT_REGEX = Regex("^\\d{1,6}(?:[.,]\\d{1,3})?$")

    // A price already combined with its currency in one node, e.g. "50.00 JD".
    private val COMBINED_PRICE_REGEX = Regex(
        "^\\d{1,6}(?:[.,]\\d{1,3})?\\s*(?:" + CURRENCY_CODES.joinToString("|") { Regex.escape(it) } + ")$",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_LABELS = setOf(
        "add", "add to cart", "+", "view cart", "items", "item", "closed", "open soon",
        "delivery", "free delivery", "ratings", "estimation time", "delivery fee"
    )

    data class RawCard(
        val texts: List<String>,
        val imageBounds: Rect?
    )

    data class ExtractedProduct(
        val name: String,
        val priceRaw: String,
        val imageBounds: Rect?
    )

    /** Walk one card's subtree collecting ordered text + the first sizeable image's bounds. */
    fun readCard(cardNode: AccessibilityNodeInfo): RawCard {
        val texts = mutableListOf<String>()
        var imageBounds: Rect? = null

        fun walk(node: AccessibilityNodeInfo) {
            val className = node.className?.toString() ?: ""
            if (imageBounds == null && className.contains("ImageView")) {
                val r = Rect()
                node.getBoundsInScreen(r)
                // Skip tiny icons (e.g. the "+" button glyph); a real
                // product photo is comfortably bigger than that.
                if (r.width() > 60 && r.height() > 60) {
                    imageBounds = r
                }
            }
            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!text.isNullOrEmpty()) {
                texts.add(text)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(cardNode)
        return RawCard(texts, imageBounds)
    }

    /** Returns null if this subtree doesn't look like a product card (no price found). */
    fun extract(raw: RawCard): ExtractedProduct? {
        var priceRaw: String? = null
        var priceTokenIndexes = emptySet<Int>()

        // Pass 1: a single node already combining amount + currency.
        for ((i, t) in raw.texts.withIndex()) {
            if (COMBINED_PRICE_REGEX.matches(t.trim())) {
                priceRaw = t.trim()
                priceTokenIndexes = setOf(i)
                break
            }
        }

        // Pass 2: a bare amount node, optionally followed by a bare currency node.
        if (priceRaw == null) {
            for ((i, t) in raw.texts.withIndex()) {
                if (AMOUNT_REGEX.matches(t.trim())) {
                    val next = raw.texts.getOrNull(i + 1)?.trim()
                    priceRaw = if (next != null && CURRENCY_CODES.any { it.equals(next, ignoreCase = true) }) {
                        priceTokenIndexes = setOf(i, i + 1)
                        "$t $next"
                    } else {
                        priceTokenIndexes = setOf(i)
                        t
                    }
                    break
                }
            }
        }

        if (priceRaw == null) return null // not a product card (e.g. a banner, header, or empty row)

        val nameCandidates = raw.texts.filterIndexed { i, t ->
            i !in priceTokenIndexes &&
                t.trim().length > 2 &&
                GENERIC_LABELS.none { it.equals(t.trim(), ignoreCase = true) } &&
                !CURRENCY_CODES.any { it.equals(t.trim(), ignoreCase = true) }
        }
        val name = nameCandidates.maxByOrNull { it.length } ?: "UNKNOWN_ITEM"

        return ExtractedProduct(name = name, priceRaw = priceRaw, imageBounds = raw.imageBounds)
    }
}
