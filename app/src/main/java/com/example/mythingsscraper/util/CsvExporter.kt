package com.example.mythingsscraper.util

import android.content.Context
import com.example.mythingsscraper.model.ScrapedItem
import java.io.File

/**
 * Writes results to app-specific external storage (no special permission
 * needed on Android 10+). MainActivity shares this file via FileProvider
 * so you can send it to yourself / save it to Drive / open in Excel.
 */
object CsvExporter {

    private const val FILE_NAME = "mythings_prices.csv"

    fun outputFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun append(context: Context, items: List<ScrapedItem>) {
        if (items.isEmpty()) return
        val file = outputFile(context)
        val writeHeader = !file.exists() || file.length() == 0L

        file.appendText(buildString {
            if (writeHeader) {
                appendLine("merchant,category,item_name,price_raw,image_path,merchant_status,scraped_at_iso")
            }
            for (item in items) {
                appendLine(
                    listOf(
                        item.merchantName,
                        item.category,
                        item.itemName,
                        item.priceRaw,
                        item.imagePath ?: "",
                        item.merchantStatus,
                        item.scrapedAtIso
                    ).joinToString(",") { csvEscape(it) }
                )
            }
        })
    }

    private fun csvEscape(value: String): String {
        val needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n")
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }
}
