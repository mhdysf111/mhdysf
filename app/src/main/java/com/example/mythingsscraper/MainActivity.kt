package com.example.mythingsscraper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.mythingsscraper.util.CsvExporter
import com.example.mythingsscraper.util.MerchantQueue
import com.example.mythingsscraper.util.ScraperLog

class MainActivity : AppCompatActivity() {

    private lateinit var editMerchantName: EditText
    private lateinit var textQueue: TextView
    private lateinit var textLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editMerchantName = findViewById(R.id.editMerchantName)
        textQueue = findViewById(R.id.textQueue)
        textLog = findViewById(R.id.textLog)

        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnAddMerchant).setOnClickListener {
            val name = editMerchantName.text.toString().trim()
            if (name.isNotEmpty()) {
                MerchantQueue.add(name)
                editMerchantName.setText("")
                refreshQueueLabel()
            }
        }

        findViewById<Button>(R.id.btnStartScraping).setOnClickListener {
            if (MerchantQueue.isEmpty()) {
                Toast.makeText(this, "Add at least one merchant first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = ScraperAccessibilityService.instance
            if (service == null) {
                Toast.makeText(
                    this,
                    "Accessibility service isn't running yet. Enable it in Settings first, " +
                        "then come back here.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            service.startProcessingQueue()
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener {
            shareCsv()
        }

        ScraperLog.history().forEach { appendLogLine(it) }
        ScraperLog.setListener { line -> appendLogLine(line) }
    }

    override fun onResume() {
        super.onResume()
        refreshQueueLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        ScraperLog.setListener(null)
    }

    private fun refreshQueueLabel() {
        val items = MerchantQueue.snapshot()
        val current = MerchantQueue.currentlyProcessing
        textQueue.text = buildString {
            append("Queue: ")
            if (items.isEmpty() && current == null) {
                append("(empty)")
            } else {
                if (current != null) append("[now: $current] ")
                append(items.joinToString(", "))
            }
        }
    }

    private fun appendLogLine(line: String) {
        textLog.append(line + "\n")
        refreshQueueLabel()
    }

    private fun shareCsv() {
        val file = CsvExporter.outputFile(this)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No data captured yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share mythings_prices.csv"))
    }
}
