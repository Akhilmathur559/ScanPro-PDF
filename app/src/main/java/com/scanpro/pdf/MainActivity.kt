package com.scanpro.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextWatcher
import android.text.Editable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val blue = Color.rgb(139, 92, 246)
    private val ink = Color.rgb(242, 244, 255)
    private val muted = Color.rgb(159, 166, 190)
    private val selectedImages = mutableListOf<Uri>()

    private val mergePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.size >= 2) mergePdfs(uris) else if (uris.isNotEmpty()) toast("Select at least two PDFs to merge")
    }
    private val splitPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) askSplitPages(uri)
    }
    private val rotatePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) rotatePdf(uri)
    }

    private val organizePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) askOrganizePages(uri)
    }

    private val pdfTextPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) extractPdfText(uri)
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages.clear()
            selectedImages.addAll(uris)
            createPdfFromUris(uris)
        }
    }

    private val ocrPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) runOcr(uri) }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = scanResult?.pdf?.uri
            if (pdfUri != null) {
                saveScannedPdf(pdfUri)
            } else {
                toast("Scan completed, but no PDF was returned")
            }
        } else if (result.resultCode != RESULT_CANCELED) {
            toast("Document scan could not be completed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        buildHome()
    }

    private fun buildHome() {
        val bg = Color.rgb(10, 12, 23)
        val panel = Color.rgb(23, 27, 44)
        val panelAlt = Color.rgb(31, 35, 56)
        val violet = Color.rgb(167, 139, 250)
        val cyan = Color.rgb(103, 232, 249)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = false }
        scroll.addView(page)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(20))
        }
        val brandIcon = TextView(this).apply {
            text = "S"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(139, 92, 246), Color.rgb(59, 130, 246))).apply { cornerRadius = dp(15).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        top.addView(brandIcon)
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brandText.addView(TextView(this).apply { text = "ScanPro"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        brandText.addView(TextView(this).apply { text = "PDF STUDIO"; textSize = 10f; letterSpacing = 0.20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(violet) })
        top.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "✦"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            background = rounded(panelAlt, 16)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        page.addView(top, matchWrap())

        page.addView(TextView(this).apply {
            text = "YOUR PAPERWORK,\\nREIMAGINED."
            textSize = 29f
            setLineSpacing(dp(1).toFloat(), 1.0f)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            letterSpacing = -0.025f
        }, matchWrap())
        page.addView(TextView(this).apply {
            text = "Everything you need. One clean workspace."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(20))
        }, matchWrap())

        val scan = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(20), dp(14), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(109, 40, 217), Color.rgb(67, 56, 202), Color.rgb(37, 99, 235))).apply { cornerRadius = dp(25).toFloat() }
            elevation = dp(5).toFloat()
            setOnClickListener { launchDocumentScanner() }
        }
        val scanCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanCopy.addView(TextView(this).apply { text = "✦  QUICK CAPTURE"; textSize = 10f; letterSpacing = 0.16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(221, 214, 254)) })
        scanCopy.addView(TextView(this).apply { text = "Scan a document"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(7), 0, dp(4)) })
        scanCopy.addView(TextView(this).apply { text = "Auto crop  ·  Enhance  ·  Save PDF"; textSize = 12f; setTextColor(Color.rgb(235, 233, 255)) })
        scan.addView(scanCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        scan.addView(TextView(this).apply { text = "↗"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = rounded(Color.rgb(255, 255, 255).let { Color.argb(38, 255, 255, 255) }, 16); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) })
        page.addView(scan, matchWrap().apply { bottomMargin = dp(25) })

        page.addView(TextView(this).apply {
            text = "THE TOOLKIT"
            textSize = 11f
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(violet)
        }, matchWrap().apply { bottomMargin = dp(12) })

        fun tile(icon: String, title: String, subtitle: String, tint: Int, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(15), dp(12), dp(15))
                background = rounded(panel, 20)
                setOnClickListener { action() }
                addView(TextView(this@MainActivity).apply {
                    text = icon; textSize = 22f; gravity = Gravity.CENTER
                    setTextColor(tint); background = rounded(panelAlt, 13)
                    layoutParams = LinearLayout.LayoutParams(dp(43), dp(43)).apply { bottomMargin = dp(13) }
                })
                addView(TextView(this@MainActivity).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
                addView(TextView(this@MainActivity).apply { text = subtitle; textSize = 11f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
            }
        }
        fun toolRow(left: View, right: View) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            page.addView(row, matchWrap().apply { bottomMargin = dp(12) })
        }
        toolRow(
            tile("▧", "Images to PDF", "Photo collection", cyan) { imagePicker.launch("image/*") },
            tile("Aa", "Text scanner", "Extract with OCR", violet) { ocrPicker.launch("image/*") }
        )
        toolRow(
            tile("⇄", "Merge PDFs", "Combine files", Color.rgb(251, 191, 36)) { mergePicker.launch("application/pdf") },
            tile("✂", "Split pages", "Pick page ranges", Color.rgb(251, 113, 133)) { splitPicker.launch("application/pdf") }
        )
        toolRow(
            tile("↻", "Rotate PDF", "Fix page orientation", cyan) { rotatePicker.launch("application/pdf") },
            tile("▤", "All documents", "Browse your library", violet) { showAllPdfs() }
        )
        toolRow(
            tile("⇅", "Organize pages", "Reorder or remove pages", Color.rgb(251, 191, 36)) { organizePicker.launch("application/pdf") },
            tile("TXT", "PDF to text", "Extract searchable text", Color.rgb(103, 232, 249)) { pdfTextPicker.launch("application/pdf") }
        )

        val saved = outputFolder().listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val libraryHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        libraryHeader.addView(TextView(this).apply {
            text = "RECENT FILES"; textSize = 11f; letterSpacing = 0.17f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(violet)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        libraryHeader.addView(TextView(this).apply {
            text = "See all  ↗"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan); setPadding(dp(8), dp(8), 0, dp(8))
            setOnClickListener { showAllPdfs() }
        })
        page.addView(libraryHeader, matchWrap().apply { topMargin = dp(12); bottomMargin = dp(10) })
        if (saved.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(18), dp(22), dp(18), dp(22)); background = rounded(panel, 20)
                addView(TextView(this@MainActivity).apply { text = "▤"; textSize = 28f; setTextColor(violet); gravity = Gravity.CENTER })
                addView(TextView(this@MainActivity).apply { text = "Your files will live here"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(4)) })
                addView(TextView(this@MainActivity).apply { text = "Create your first scan to get started"; textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER })
            }
            page.addView(empty, matchWrap())
        } else {
            val totalBytes = saved.sumOf { it.length() }
            val stats = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 16)
            }
            fun stat(value: String, caption: String): LinearLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
                addView(TextView(this@MainActivity).apply { text = caption; textSize = 10f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
            }
            stats.addView(stat(saved.size.toString(), "PDF FILES"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            stats.addView(stat(if (totalBytes > 1024 * 1024) String.format("%.1f MB", totalBytes / (1024f * 1024f)) else "${totalBytes / 1024} KB", "SPACE USED"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            page.addView(stats, matchWrap().apply { bottomMargin = dp(12) })
            val search = EditText(this).apply {
                hint = "Search your PDFs..."
                textSize = 14f
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(120, 128, 155))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(panelAlt, 16)
            }
            page.addView(search, matchWrap().apply { bottomMargin = dp(10) })
            val fileResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            page.addView(fileResults, matchWrap())
            fun renderFiles(query: String) {
                fileResults.removeAllViews()
                val matches = saved.filter { it.name.contains(query.trim(), ignoreCase = true) }
                if (matches.isEmpty()) {
                    fileResults.addView(TextView(this).apply {
                        text = "No matching PDFs found"
                        textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
                        setPadding(dp(12), dp(22), dp(12), dp(22))
                    }, matchWrap())
                } else {
                    matches.take(5).forEach { pdf -> addPdfRow(fileResults, pdf) }
                    if (matches.size > 5) {
                        addTextButton(fileResults, "View all ${matches.size} matching PDFs") { showAllPdfs() }
                    }
                }
            }
            renderFiles("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderFiles(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        page.addView(TextView(this).apply {
            text = "PRIVATE BY DESIGN  ·  PROCESSED ON YOUR DEVICE"
            textSize = 9f; letterSpacing = 0.10f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 112, 140)); setPadding(0, dp(24), 0, dp(8))
        }, matchWrap())

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.rgb(23, 27, 44), 22)
            elevation = dp(10).toFloat()
        }
        fun navItem(symbol: String, label: String, active: Boolean, action: () -> Unit): TextView =
            TextView(this).apply {
                text = "$symbol\\n$label"; gravity = Gravity.CENTER; textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else muted)
                if (active) background = rounded(Color.rgb(104, 74, 210), 15)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener { action() }
            }
        nav.addView(navItem("◈", "HOME", true) { buildHome() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(navItem("▤", "LIBRARY", false) { showAllPdfs() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(navItem("✧", "TOOLS", false) {
            AlertDialog.Builder(this@MainActivity).setTitle("PDF Studio")
                 .setItems(arrayOf("Scan document", "Images to PDF", "Text recognition (OCR)", "Merge PDFs", "Split PDF pages", "Rotate PDF pages", "Organize / reorder pages", "Extract text from PDF")) { _, which ->
                    when (which) {
                        0 -> launchDocumentScanner()
                        1 -> imagePicker.launch("image/*")
                        2 -> ocrPicker.launch("image/*")
                        3 -> mergePicker.launch("application/pdf")
                        4 -> splitPicker.launch("application/pdf")
                        5 -> rotatePicker.launch("application/pdf")
                        6 -> organizePicker.launch("application/pdf")
                        7 -> pdfTextPicker.launch("application/pdf")
                    }
                }.show()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav, matchWrap().apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(8); bottomMargin = dp(10) })
        setContentView(root)
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(12))
        }, matchWrap().apply { topMargin = dp(2) })
    }

    private fun addAction(
        parent: LinearLayout, icon: String, title: String, subtitle: String,
        buttonLabel: String, action: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(25, 29, 47), 22)
            elevation = dp(3).toFloat()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = icon
            textSize = 25f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(47, 39, 83), 15)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        })
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        textColumn.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        val button = Button(this).apply {
            text = buttonLabel
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(blue, 10)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { action() }
        }
        card.addView(button, matchWrap().apply { topMargin = dp(13) })
        parent.addView(card, matchWrap().apply { bottomMargin = dp(13) })
        return card
    }

    private fun addPdfRow(parent: LinearLayout, pdf: File) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(10), dp(12))
            background = rounded(Color.rgb(25, 29, 47), 17)
            elevation = dp(1).toFloat()
            setOnClickListener { showPdfOptions(pdf) }
        }
        row.addView(TextView(this).apply {
            text = "PDF"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(blue)
            background = rounded(Color.rgb(47, 39, 83), 10)
            setPadding(dp(9), dp(13), dp(9), dp(13))
        })
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(TextView(this).apply {
            text = pdf.name
            textSize = 14f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(TextView(this).apply {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pdf.lastModified()))
            text = "$date  •  ${pdf.length() / 1024} KB"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "⋮"
            textSize = 24f
            setTextColor(muted)
            setPadding(dp(8), 0, dp(2), 0)
        })
        parent.addView(row, matchWrap().apply { bottomMargin = dp(9) })
    }

    private fun showAllPdfs() {
        val files = outputFolder().listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val bg = Color.rgb(10, 12, 23)
        val panel = Color.rgb(23, 27, 44)
        val violet = Color.rgb(167, 139, 250)
        val cyan = Color.rgb(103, 232, 249)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            setBackgroundColor(bg)
        }
        shell.addView(TextView(this).apply {
            text = "YOUR LIBRARY"
            textSize = 11f; letterSpacing = 0.18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(violet)
        }, matchWrap())
        shell.addView(TextView(this).apply {
            text = "Every file,\\none place."
            textSize = 27f; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(Color.WHITE); setPadding(0, dp(7), 0, dp(4))
        }, matchWrap())
        shell.addView(TextView(this).apply {
            text = "${files.size} PDF files · stored privately on this device"
            textSize = 12f; setTextColor(muted); setPadding(0, 0, 0, dp(16))
        }, matchWrap())
        val search = EditText(this).apply {
            hint = "Search by file name..."
            textSize = 14f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 128, 155))
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(panel, 15)
        }
        shell.addView(search, matchWrap().apply { bottomMargin = dp(12) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        scroller.addView(list)
        shell.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420), 1f))

        fun render(query: String) {
            list.removeAllViews()
            val matches = files.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (matches.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = if (files.isEmpty()) "No PDFs yet. Start by scanning a document." else "No files match that search."
                    textSize = 14f; gravity = Gravity.CENTER; setTextColor(muted)
                    setPadding(dp(12), dp(35), dp(12), dp(35))
                }, matchWrap())
            } else {
                matches.forEach { pdf ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = rounded(panel, 17)
                        setOnClickListener { showPdfOptions(pdf) }
                    }
                    card.addView(TextView(this).apply {
                        text = "PDF"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER; setTextColor(violet)
                        background = rounded(Color.rgb(47, 39, 83), 11)
                        setPadding(dp(10), dp(14), dp(10), dp(14))
                    })
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, dp(8), 0)
                    }
                    details.addView(TextView(this).apply {
                        text = pdf.name; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    details.addView(TextView(this).apply {
                        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pdf.lastModified()))
                        val size = if (pdf.length() >= 1024 * 1024) String.format("%.1f MB", pdf.length() / (1024f * 1024f)) else "${pdf.length() / 1024} KB"
                        text = "$date  ·  $size"; textSize = 11f; setTextColor(muted)
                        setPadding(0, dp(4), 0, 0)
                    })
                    card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    card.addView(TextView(this).apply {
                        text = "›"; textSize = 25f; setTextColor(cyan)
                    })
                    list.addView(card, matchWrap().apply { bottomMargin = dp(8) })
                }
            }
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        AlertDialog.Builder(this)
            .setView(shell)
            .setNegativeButton("Close", null)
            .setPositiveButton("Scan new", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        dialog.dismiss()
                        launchDocumentScanner()
                    }
                }
                dialog.show()
            }
    }

    private fun showPdfOptions(pdf: File) {
        val actions = arrayOf("Open PDF", "Share PDF", "Rename PDF", "Delete PDF")
        AlertDialog.Builder(this)
            .setTitle(pdf.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openPdf(pdf)
                    1 -> sharePdf(pdf)
                    2 -> renamePdf(pdf)
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Delete this PDF?")
                        .setMessage("This will permanently delete ${pdf.name}.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            if (pdf.delete()) {
                                toast("PDF deleted")
                                buildHome()
                            } else toast("Could not delete this file")
                        }.show()
                }
            }.show()
    }

    private fun openPdf(pdf: File) {
        if (!pdf.exists() || !pdf.isFile || pdf.length() == 0L) {
            toast("PDF file is missing or empty")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                clipData = ClipData.newUri(contentResolver, pdf.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Do not rely on resolveActivity(): Android package-visibility rules can
            // return null even when a PDF viewer is installed. Let the system chooser resolve it.
            startActivity(Intent.createChooser(intent, "Open PDF with"))
        } catch (e: Exception) {
            toast("Could not open PDF: " + (e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    private fun sharePdf(pdf: File) {
        if (!pdf.exists() || !pdf.isFile || pdf.length() == 0L) {
            toast("PDF file is missing or empty")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, pdf.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (e: Exception) {
            toast("Could not share PDF: " + (e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    private fun renamePdf(pdf: File) {
        val input = android.widget.EditText(this).apply {
            setText(pdf.nameWithoutExtension)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename PDF")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isBlank()) {
                    toast("Enter a file name")
                } else {
                    val target = File(pdf.parentFile, "$name.pdf")
                    if (target.exists()) toast("A PDF with this name already exists")
                    else if (pdf.renameTo(target)) {
                        toast("PDF renamed")
                        buildHome()
                    } else toast("Could not rename PDF")
                }
            }.show()
    }

    private fun launchDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { error ->
                toast("Scanner could not start: " + (error.localizedMessage ?: "check Google Play services"))
            }
    }

    private fun saveScannedPdf(uri: Uri) {
        try {
            val folder = outputFolder()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "ScanPro_Scan_" + System.currentTimeMillis() + ".pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: run {
                toast("Could not read the scanned PDF")
                return
            }
            toast("Scanned PDF saved: " + file.name)
            buildHome()
        } catch (error: Exception) {
            toast("Could not save scanned PDF: " + (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun createPdfFromUris(uris: List<Uri>) {
        val bitmaps = mutableListOf<Bitmap>()
        try {
            uris.forEach { uri ->
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bitmaps.add(it) }
                }
            }
            if (bitmaps.isEmpty()) {
                toast("Could not read the selected images")
                return
            }
            createPdfFromBitmaps(bitmaps)
        } catch (e: Exception) {
            toast("PDF creation failed: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun createPdfFromBitmaps(bitmaps: List<Bitmap>) {
        val document = PdfDocument()
        try {
            bitmaps.forEachIndexed { index, bitmap ->
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, index + 1).create()
                val page = document.startPage(pageInfo)
                val margin = 24f
                val scale = min((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (595f - width) / 2f
                val top = (842f - height) / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
                document.finishPage(page)
            }
            val folder = outputFolder()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "ScanPro_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { document.writeTo(it) }
            toast("PDF saved: ${file.name}")
            buildHome()
        } catch (e: Exception) {
            toast("Could not save PDF: ${e.localizedMessage ?: "unknown error"}")
        } finally {
            document.close()
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun runOcr(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.ifBlank { "No readable text found." }
                    AlertDialog.Builder(this)
                        .setTitle("Recognized text")
                        .setMessage(text)
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Copy") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Recognized text", text))
                            toast("Text copied")
                        }.show()
                }
                .addOnFailureListener { e -> toast("OCR failed: ${e.localizedMessage ?: "unknown error"}") }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Exception) {
            toast("Could not open image: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun mergePdfs(uris: List<Uri>) {
        try {
            val output = PDDocument()
            var addedPages = 0
            try {
                uris.forEach { uri ->
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not read one selected PDF")
                    input.use { stream ->
                        val source = PDDocument.load(stream)
                        try {
                            for (pageIndex in 0 until source.numberOfPages) {
                                output.importPage(source.getPage(pageIndex))
                                addedPages++
                            }
                        } finally {
                            source.close()
                        }
                    }
                }
                if (addedPages == 0) throw IllegalStateException("Selected PDFs contain no pages")
                val folder = outputFolder()
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, "ScanPro_Merged_${System.currentTimeMillis()}.pdf")
                output.save(file)
                toast("Merged $addedPages pages: ${file.name}")
                buildHome()
            } finally {
                output.close()
            }
        } catch (e: Exception) {
            toast("Merge failed: ${e.localizedMessage ?: "unsupported or damaged PDF"}")
        }
    }

    private fun askSplitPages(uri: Uri) {
        val input = EditText(this).apply {
            hint = "Example: 1-3 or 2,4,6"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Split / extract pages")
            .setMessage("Enter page numbers or ranges. Example: 1-3,5")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create PDF") { _, _ -> splitPdf(uri, input.text.toString()) }
            .show()
    }

    private fun splitPdf(uri: Uri, pageSpec: String) {
        try {
            val stream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not read selected PDF")
            stream.use { input ->
                val source = PDDocument.load(input)
                try {
                    val selected = linkedSetOf<Int>()
                    pageSpec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                        if (part.contains("-")) {
                            val bits = part.split("-", limit = 2)
                            val start = bits[0].trim().toInt()
                            val end = bits[1].trim().toInt()
                            if (start < 1 || end < start || end > source.numberOfPages) {
                                throw IllegalArgumentException("Page range $part is outside 1-${source.numberOfPages}")
                            }
                            for (page in start..end) selected.add(page)
                        } else {
                            val page = part.toInt()
                            if (page < 1 || page > source.numberOfPages) {
                                throw IllegalArgumentException("Page $page is outside 1-${source.numberOfPages}")
                            }
                            selected.add(page)
                        }
                    }
                    if (selected.isEmpty()) throw IllegalArgumentException("Enter at least one page number")
                    val output = PDDocument()
                    try {
                        selected.forEach { output.importPage(source.getPage(it - 1)) }
                        val folder = outputFolder()
                        if (!folder.exists()) folder.mkdirs()
                        val file = File(folder, "ScanPro_Pages_${System.currentTimeMillis()}.pdf")
                        output.save(file)
                        toast("Saved ${selected.size} page(s): ${file.name}")
                        buildHome()
                    } finally {
                        output.close()
                    }
                } finally {
                    source.close()
                }
            }
        } catch (e: Exception) {
            toast("Split failed: ${e.localizedMessage ?: "check page numbers and PDF"}")
        }
    }

    private fun askOrganizePages(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not read selected PDF")
            val pageCount = stream.use { input ->
                PDDocument.load(input).use { it.numberOfPages }
            }
            if (pageCount < 1) throw IllegalArgumentException("This PDF has no pages")
            val orderInput = EditText(this).apply {
                hint = "Example: 3,1,2"
                setSingleLine(true)
                setText((1..pageCount).joinToString(","))
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Organize PDF pages")
                .setMessage("This PDF has $pageCount pages. Enter the page numbers in the order you want. Omit a number to remove that page; repeat a number to duplicate it. Example: 3,1,2")
                .setView(orderInput)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create PDF") { _, _ -> organizePdf(uri, orderInput.text.toString(), pageCount) }
                .show()
        } catch (e: Exception) {
            toast("Could not read PDF: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun organizePdf(uri: Uri, pageSpec: String, pageCount: Int) {
        try {
            val requested = pageSpec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .map { it.toIntOrNull() ?: throw IllegalArgumentException("Use page numbers separated by commas") }
            if (requested.isEmpty()) throw IllegalArgumentException("Enter at least one page number")
            if (requested.any { it !in 1..pageCount }) throw IllegalArgumentException("Page numbers must be between 1 and $pageCount")
            contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { source ->
                    PDDocument().use { output ->
                        requested.forEach { output.importPage(source.getPage(it - 1)) }
                        val folder = outputFolder()
                        if (!folder.exists()) folder.mkdirs()
                        val file = File(folder, "ScanPro_Organized_${System.currentTimeMillis()}.pdf")
                        output.save(file)
                        toast("Created PDF with ${requested.size} pages")
                        buildHome()
                    }
                }
            } ?: throw IllegalStateException("Could not read selected PDF")
        } catch (e: Exception) {
            toast("Page organization failed: ${e.localizedMessage ?: "check page numbers"}")
        }
    }

    private fun extractPdfText(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val text = PDFTextStripper().getText(document).trim()
                    if (text.isBlank()) {
                        toast("No selectable text found. Scanned PDFs may need OCR.")
                        return
                    }
                    val folder = outputFolder()
                    if (!folder.exists()) folder.mkdirs()
                    val file = File(folder, "ScanPro_Text_${System.currentTimeMillis()}.txt")
                    file.writeText(text, Charsets.UTF_8)
                    AlertDialog.Builder(this)
                        .setTitle("Text extracted")
                        .setMessage("Saved extracted text to ${file.name}.\n\n" + text.take(1800) + if (text.length > 1800) "\n…" else "")
                        .setPositiveButton("Done", null)
                        .setNeutralButton("Copy all") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
                            toast("Extracted text copied")
                        }
                        .show()
                    toast("Text saved as ${file.name}")
                }
            } ?: throw IllegalStateException("Could not read selected PDF")
        } catch (e: Exception) {
            toast("Text extraction failed: ${e.localizedMessage ?: "unsupported or damaged PDF"}")
        }
    }

    private fun rotatePdf(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not read selected PDF")
            stream.use { input ->
                val document = PDDocument.load(input)
                try {
                    for (index in 0 until document.numberOfPages) {
                        val page = document.getPage(index)
                        page.rotation = (page.rotation + 90) % 360
                    }
                    val folder = outputFolder()
                    if (!folder.exists()) folder.mkdirs()
                    val file = File(folder, "ScanPro_Rotated_${System.currentTimeMillis()}.pdf")
                    document.save(file)
                    toast("Rotated all pages: ${file.name}")
                    buildHome()
                } finally {
                    document.close()
                }
            }
        } catch (e: Exception) {
            toast("Rotate failed: ${e.localizedMessage ?: "unsupported or damaged PDF"}")
        }
    }

    private fun outputFolder(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ScanPro")

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun addTextButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }, matchWrap())
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
