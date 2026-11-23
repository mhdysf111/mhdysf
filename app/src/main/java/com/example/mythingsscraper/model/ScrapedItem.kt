package com.example.mythingsscraper.model

/**
 * One scraped product row. `category` is the catalog tab it came from
 * (e.g. "Dry Cat Food") since Mythings splits a store's catalog across
 * tabs rather than showing everything in one list.
 */
data class ScrapedItem(
    val merchantName: String,
    val category: String,
    val itemName: String,
    val priceRaw: String,
    val imagePath: String?,
    val scrapedAtIso: String,
    val merchantStatus: String // "open", "closed", or "unknown" - from the search result badge
)
