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
import android.content.pm.PackageManager
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
import android.widget.SeekBar
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
import java.util.concurrent.Executors
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

    private val optimizePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) optimizePdf(uri)
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

    private var batchScanMode = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var batchCapture: ImageCapture? = null
    private var batchAnalysis: ImageAnalysis? = null
    private var liveAnalyzer: LiveDocumentAnalyzer? = null
    private var cornerOverlay: DocumentCornerOverlay? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val liveBatchPages = mutableListOf<ScannedPage>()
    private var batchCameraRoot: View? = null

    private data class ScannedPage(val uri: Uri, var name: String)

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages.orEmpty().mapIndexedNotNull { index, page ->
                page.imageUri?.let { ScannedPage(it, "Page_${(index + 1).toString().padStart(2, '0')}") }
            }
            if (batchScanMode && pages.isNotEmpty()) {
                showBatchPreview(pages)
            } else {
                val pdfUri = scanResult?.pdf?.uri
                if (pdfUri != null) saveScannedPdf(pdfUri)
                else if (pages.isNotEmpty()) showBatchPreview(pages)
                else toast("Scan completed, but no page images or PDF were returned")
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
            setOnClickListener { showScanModeChooser() }
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
        nav.addView(navItem("✧", "TOOLS", false) { showToolsPage() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun showAllPdfs() { showLibraryPage() }

    private fun secondaryPage(title: String, subtitle: String, active: Int, content: (LinearLayout) -> Unit) {
        val bg = Color.rgb(10, 12, 23)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)); setBackgroundColor(bg) }
        page.addView(TextView(this).apply { text = title; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, matchWrap())
        page.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(muted); setPadding(0, dp(6), 0, dp(20)) }, matchWrap())
        content(page)
        val scroll = ScrollView(this).apply { addView(page) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(Color.rgb(23, 27, 44), 22) }
        fun item(label: String, index: Int, action: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (active == index) Color.WHITE else muted); if (active == index) background = rounded(Color.rgb(104, 74, 210), 15); setPadding(dp(8), dp(13), dp(8), dp(13)); setOnClickListener { action() } }
        nav.addView(item("HOME", 0) { buildHome() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(item("LIBRARY", 1) { showLibraryPage() }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(item("TOOLS", 2) { showToolsPage() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(nav, matchWrap().apply { leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(8); bottomMargin = dp(10) })
        setContentView(root)
    }

    private fun showLibraryPage() {
        secondaryPage("Your Library", "Search, sort and manage all saved PDFs.", 1) { page ->
            val files: List<File> = outputFolder()
                .listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
                ?.toList()
                .orEmpty()
            var sortMode = 0 // 0 newest, 1 oldest, 2 name, 3 largest
            val totalBytes = files.sumOf { it.length() }
            val totalLabel = if (totalBytes >= 1024L * 1024L) String.format("%.1f MB", totalBytes / (1024f * 1024f)) else "${totalBytes / 1024} KB"
            page.addView(TextView(this).apply {
                text = "${files.size} PDFs  ·  $totalLabel used"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(103, 232, 249)); setPadding(0, 0, 0, dp(12))
            }, matchWrap())
            val search = EditText(this).apply {
                hint = "Search documents"; setSingleLine(true); textSize = 14f
                setTextColor(Color.WHITE); setHintTextColor(muted)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.rgb(31, 35, 56), 16)
            }
            page.addView(search, matchWrap().apply { bottomMargin = dp(8) })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            page.addView(list, matchWrap())
            val sortButton = Button(this).apply {
                text = "Sort: Newest first"; isAllCaps = false; textSize = 12f
            }
            fun render(q: String) { renderLibrary(q, sortMode, files, list) }
            sortButton.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sort PDFs")
                    .setSingleChoiceItems(arrayOf("Newest first", "Oldest first", "Name A–Z", "Largest first"), sortMode) { dialog, which ->
                        sortMode = which
                        sortButton.text = "Sort: " + arrayOf("Newest first", "Oldest first", "Name A–Z", "Largest first")[which]
                        render(search.text.toString())
                        dialog.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
            page.addView(sortButton, 0, matchWrap().apply { bottomMargin = dp(10) })
            render("")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
            page.addView(TextView(this).apply {
                text = "+ Scan new document"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = rounded(Color.rgb(104, 74, 210), 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setOnClickListener { showScanModeChooser() }
            }, matchWrap().apply { topMargin = dp(14) })
        }
    }

    private fun renderLibrary(query: String, sortMode: Int, files: List<File>, list: LinearLayout) {
        list.removeAllViews()
        val filtered = files.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val matches = when (sortMode) {
            1 -> filtered.sortedBy { it.lastModified() }
            2 -> filtered.sortedBy { it.name.lowercase() }
            3 -> filtered.sortedByDescending { it.length() }
            else -> filtered.sortedByDescending { it.lastModified() }
        }
        if (matches.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (files.isEmpty()) "No PDFs yet. Scan a document to start." else "No matching PDFs."
                setTextColor(muted); gravity = Gravity.CENTER
                setPadding(dp(10), dp(32), dp(10), dp(32))
            }, matchWrap())
        } else {
            matches.forEach { addPdfRow(list, it) }
        }
    }

    private fun showToolsPage() {
        secondaryPage("PDF Tools", "Choose a tool to get straight to work.", 2) { page ->
            fun tool(name: String, detail: String, action: () -> Unit) { val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = rounded(Color.rgb(23, 27, 44), 18); setOnClickListener { action() } }; card.addView(TextView(this).apply { text = name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }); card.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, 0) }); page.addView(card, matchWrap().apply { bottomMargin = dp(10) }) }
            tool("Scan document", "Choose single or batch scanning") { showScanModeChooser() }
            tool("Images to PDF", "Create a PDF from photos") { imagePicker.launch("image/*") }
            tool("Text scanner / OCR", "Recognize text from an image") { ocrPicker.launch("image/*") }
            tool("Merge PDFs", "Combine multiple PDF files") { mergePicker.launch("application/pdf") }
            tool("Split pages", "Extract selected page ranges") { splitPicker.launch("application/pdf") }
            tool("Rotate PDF", "Rotate all pages") { rotatePicker.launch("application/pdf") }
            tool("Optimize PDF", "Rewrite PDF and compare file size") { optimizePicker.launch("application/pdf") }
            tool("Organize pages", "Reorder, remove or duplicate pages") { organizePicker.launch("application/pdf") }
            tool("PDF to text", "Extract text into a TXT file") { pdfTextPicker.launch("application/pdf") }
            tool("Browse Library", "Search and manage saved PDFs") { showLibraryPage() }
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

    private fun showScanModeChooser() {
        AlertDialog.Builder(this)
            .setTitle("Choose scan mode")
            .setMessage("Single Document: scan one page/document. Batch Document: scan multiple pages, then review and export.")
            .setNegativeButton("Single Document") { _, _ -> launchDocumentScanner(false) }
            .setPositiveButton("Batch Document") { _, _ -> launchDocumentScanner(true) }
            .show()
    }

    private fun launchDocumentScanner(batch: Boolean) {
        batchScanMode = batch
        if (batch) {
            liveBatchPages.clear()
            openContinuousBatchCamera()
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (batch) 30 else 1)
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


    /**
     * A persistent CameraX preview for batch scans. Unlike the Google document-scanner
     * UI, the camera stays open between page captures; users never need to press its "+".
     */
    private fun openContinuousBatchCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 721)
            return
        }
        val black = Color.BLACK
        val accent = Color.rgb(20, 190, 169)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(black)
            fitsSystemWindows = false
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(black)
        }
        fun tool(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { action() }
        }
        toolbar.addView(tool("×") {
            cameraProvider?.unbindAll()
            if (liveBatchPages.isEmpty()) buildHome() else showBatchPreview(liveBatchPages.toList())
        }, LinearLayout.LayoutParams(dp(48), dp(46)))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val flash = tool("ϟ") { toast("Flash control will be added next") }
        toolbar.addView(flash, LinearLayout.LayoutParams(dp(44), dp(46)))
        toolbar.addView(tool("✧") { toast("Scan enhancement is applied after capture") }, LinearLayout.LayoutParams(dp(44), dp(46)))
        toolbar.addView(tool("HD") { toast("High-quality capture is enabled") }, LinearLayout.LayoutParams(dp(48), dp(46)))
        toolbar.addView(tool("⋮") {
            AlertDialog.Builder(this)
                .setItems(arrayOf("Finish batch", "Cancel batch", "Scan tips")) { _, which ->
                    when (which) {
                        0 -> { cameraProvider?.unbindAll(); if (liveBatchPages.isEmpty()) buildHome() else showBatchPreview(liveBatchPages.toList()) }
                        1 -> { cameraProvider?.unbindAll(); liveBatchPages.clear(); buildHome() }
                        2 -> toast("Keep the full page in view and use even lighting")
                    }
                }.show()
        }, LinearLayout.LayoutParams(dp(40), dp(46)))
        root.addView(toolbar, matchWrap())

        val heading = TextView(this).apply {
            text = "BATCH  •  0 PAGES"
            textSize = 12f
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(7))
        }
        root.addView(heading, matchWrap())

        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        val previewFrame = FrameLayout(this)
        previewFrame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlay = DocumentCornerOverlay(this)
        cornerOverlay = overlay
        previewFrame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(3))
            setBackgroundColor(black)
        }
        fun mode(label: String, selected: Boolean, action: () -> Unit) {
            modeRow.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) accent else Color.LTGRAY)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        mode("Single", false) {
            cameraProvider?.unbindAll()
            batchScanMode = false
            launchDocumentScanner(false)
        }
        mode("Batch", true) { }
        mode("ID Card", false) {
            toast("ID Card mode is coming next; use Batch for now")
        }
        root.addView(modeRow, matchWrap())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(9))
            setBackgroundColor(black)
        }
        fun sideAction(label: String, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setOnClickListener { action() }
                addView(TextView(this@MainActivity).apply {
                    text = if (label == "All Features") "▦" else if (label == "Import Images") "▧" else "▤"
                    textSize = 23f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }, matchWrap())
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }, matchWrap())
            }
        }
        controls.addView(sideAction("All Features") {
            cameraProvider?.unbindAll()
            buildHome()
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        val capture = TextView(this).apply {
            text = "●"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(245, 245, 245))
                setStroke(dp(5), accent)
            }
            setOnClickListener {
                val useCase = batchCapture ?: run { toast("Camera is still starting"); return@setOnClickListener }
                val folder = File(cacheDir, "batch_scan").apply { if (!exists()) mkdirs() }
                val output = File(folder, "page_${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(output).build()
                isEnabled = false
                val oldText = text
                text = "…"
                useCase.takePicture(options, ContextCompat.getMainExecutor(this@MainActivity), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        text = oldText
                        Thread {
                            val processedFile = DocumentImageProcessor.process(output)
                            runOnUiThread {
                                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", processedFile)
                                liveBatchPages.add(ScannedPage(uri, "Page_${liveBatchPages.size.plus(1).toString().padStart(2, '0')}"))
                                heading.text = "BATCH  •  ${liveBatchPages.size} PAGES"
                                isEnabled = true
                                toast("Page ${liveBatchPages.size} captured")
                            }
                        }.start()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        text = oldText
                        isEnabled = true
                        toast("Capture failed: ${exception.message ?: "try again"}")
                    }
                })
            }
        }
        controls.addView(capture, LinearLayout.LayoutParams(dp(84), dp(84)).apply { leftMargin = dp(2); rightMargin = dp(2) })
        controls.addView(sideAction("Import Images") {
            cameraProvider?.unbindAll()
            imagePicker.launch("image/*")
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        controls.addView(sideAction("Import Files") {
            cameraProvider?.unbindAll()
            mergePicker.launch("application/pdf")
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        root.addView(controls, matchWrap())

        val finishRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(5))
            setBackgroundColor(black)
        }
        finishRow.addView(TextView(this).apply {
            text = "Finish batch  •  ${liveBatchPages.size} page(s)"
            textSize = 12f
            setTextColor(if (liveBatchPages.isEmpty()) Color.GRAY else accent)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener {
                cameraProvider?.unbindAll()
                batchCapture = null
                batchAnalysis = null
                liveAnalyzer = null
                cornerOverlay = null
                batchCameraRoot = null
                if (liveBatchPages.isEmpty()) buildHome() else showBatchPreview(liveBatchPages.toList())
            }
        }, matchWrap())
        root.addView(finishRow, matchWrap())

        batchCameraRoot = root
        setContentView(root)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(95)
                    .build()
                batchCapture = imageCapture
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                val analyzer = LiveDocumentAnalyzer { corners ->
                    runOnUiThread { cornerOverlay?.updateCorners(corners) }
                }
                liveAnalyzer = analyzer
                batchAnalysis = analysis
                analysis.setAnalyzer(analysisExecutor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageCapture, analysis)
            } catch (e: Exception) {
                toast("Could not start camera: ${e.localizedMessage ?: "camera unavailable"}")
                buildHome()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 721) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openContinuousBatchCamera()
            } else {
                toast("Camera permission is required for continuous batch scanning")
                buildHome()
            }
        }
    }

    private fun showBatchPreview(pages: List<ScannedPage>) {
        val items = pages.toMutableList()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Review ${items.size} scanned page(s)")
            .setView(scroller)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Save options", null)
            .setPositiveButton("ZIP: individual PDFs", null)
            .create()

        fun renderRows() {
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = "Rename pages, move them up/down, or remove pages. ZIP export creates one PDF per page only—no combined PDF and no JPEG."
                textSize = 13f
                setTextColor(muted)
                setPadding(0, 0, 0, dp(12))
            }, matchWrap())
            items.forEachIndexed { index, item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = rounded(Color.rgb(25, 29, 47), 14)
                }
                val name = EditText(this).apply {
                    setText(item.name)
                    hint = "Page file name"
                    setSingleLine(true)
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setHintTextColor(muted)
                }
                row.addView(TextView(this).apply {
                    text = "Page ${index + 1}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(167, 139, 250))
                }, matchWrap())
                row.addView(name, matchWrap())
                val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun control(label: String, action: () -> Unit) {
                    controls.addView(Button(this@MainActivity).apply {
                        text = label
                        isAllCaps = false
                        textSize = 11f
                        setOnClickListener {
                            items.forEachIndexed { rowIndex, rowItem ->
                                val visibleRow = content.getChildAt(rowIndex + 1) as? LinearLayout
                                val visibleName = visibleRow?.getChildAt(1) as? EditText
                                visibleName?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { rowItem.name = it }
                            }
                            action()
                        }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
                }
                control("↑") {
                    if (index > 0) {
                        val moved = items.removeAt(index)
                        items.add(index - 1, moved)
                        renderRows()
                    }
                }
                control("↓") {
                    if (index < items.lastIndex) {
                        val moved = items.removeAt(index)
                        items.add(index + 1, moved)
                        renderRows()
                    }
                }
                control("Delete") {
                    items.removeAt(index)
                    renderRows()
                }
                row.addView(controls, matchWrap())
                content.addView(row, matchWrap().apply { bottomMargin = dp(8) })
            }
        }
        renderRows()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                items.forEachIndexed { index, item ->
                    val row = content.getChildAt(index + 1) as? LinearLayout
                    val edit = row?.getChildAt(1) as? EditText
                    edit?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { item.name = it }
                }
                showBatchExportOptions(items)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                items.forEachIndexed { index, item ->
                    val row = content.getChildAt(index + 1) as? LinearLayout
                    val edit = row?.getChildAt(1) as? EditText
                    edit?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { item.name = it }
                }
                if (items.isEmpty()) {
                    toast("Keep at least one page to export")
                } else {
                    exportPagesAsZip(items, 85)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.78).toInt())
    }

    private fun showBatchExportOptions(pages: List<ScannedPage>) {
        if (pages.isEmpty()) {
            toast("Keep at least one page to export")
            return
        }
        val choices = arrayOf("Combined PDF", "Separate JPEG images", "ZIP of individual-page PDFs")
        AlertDialog.Builder(this)
            .setTitle("Export ${pages.size} page(s)")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> askCompression(85) { quality -> exportCombinedPdf(pages, quality) }
                    1 -> askCompression(85) { quality -> exportJpegs(pages, quality) }
                    2 -> askCompression(85) { quality -> exportPagesAsZip(pages, quality) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askCompression(defaultQuality: Int, onSelected: (Int) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val valueLabel = TextView(this).apply {
            text = "Image quality: $defaultQuality%"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        val slider = android.widget.SeekBar(this).apply {
            max = 60
            progress = (defaultQuality - 40).coerceIn(0, 60)
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                valueLabel.text = "Image quality: ${progress + 40}% (lower size at lower quality)"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        layout.addView(valueLabel, matchWrap())
        layout.addView(slider, matchWrap())
        AlertDialog.Builder(this)
            .setTitle("Compression")
            .setMessage("Choose image quality. Lower quality usually makes smaller files.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Export") { _, _ -> onSelected(slider.progress + 40) }
            .show()
    }

    private fun loadScannedBitmap(uri: Uri, quality: Int): Bitmap {
        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Could not read a scanned page")
        if (quality >= 100) return original
        val bytes = java.io.ByteArrayOutputStream()
        original.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        val compressed = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
            ?: return original
        if (compressed !== original) original.recycle()
        return compressed
    }

    private fun safePageName(name: String, index: Int): String {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.')
        return (clean.ifBlank { "Page_${(index + 1).toString().padStart(2, '0')}" }).take(100)
    }

    private fun exportCombinedPdf(pages: List<ScannedPage>, quality: Int) {
        val bitmaps = mutableListOf<Bitmap>()
        try {
            pages.forEach { bitmaps.add(loadScannedBitmap(it.uri, quality)) }
            if (bitmaps.isEmpty()) throw IllegalStateException("No pages to export")
            val folder = outputFolder()
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create ScanPro folder")
            val file = File(folder, "ScanPro_Batch_${System.currentTimeMillis()}.pdf")
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
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                }
                FileOutputStream(file).use { document.writeTo(it) }
            } finally { document.close() }
            toast("Combined PDF saved: ${file.name}")
            buildHome()
        } catch (e: Exception) {
            toast("PDF export failed: ${e.localizedMessage ?: "unknown error"}")
        } finally { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
    }

    private fun exportJpegs(pages: List<ScannedPage>, quality: Int) {
        try {
            val folder = File(outputFolder(), "Batch_${System.currentTimeMillis()}")
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create export folder")
            pages.forEachIndexed { index, page ->
                val bitmap = loadScannedBitmap(page.uri, quality)
                try {
                    val file = File(folder, safePageName(page.name, index) + ".jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                } finally { bitmap.recycle() }
            }
            toast("Saved ${pages.size} JPEG page(s) in ${folder.name}")
            buildHome()
        } catch (e: Exception) {
            toast("JPEG export failed: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun exportPagesAsZip(pages: List<ScannedPage>, quality: Int) {
        val tempFiles = mutableListOf<File>()
        try {
            val folder = outputFolder()
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Could not create ScanPro folder")
            val stamp = System.currentTimeMillis()
            val zipFile = File(folder, "ScanPro_Pages_$stamp.zip")
            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                pages.forEachIndexed { index, page ->
                    val bitmap = loadScannedBitmap(page.uri, quality)
                    val temp = File(cacheDir, "scanpro_page_${stamp}_$index.pdf")
                    tempFiles.add(temp)
                    val document = PdfDocument()
                    try {
                        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                        val pdfPage = document.startPage(pageInfo)
                        val margin = 24f
                        val scale = min((595f - margin * 2) / bitmap.width, (842f - margin * 2) / bitmap.height)
                        val width = bitmap.width * scale
                        val height = bitmap.height * scale
                        val left = (595f - width) / 2f
                        val top = (842f - height) / 2f
                        pdfPage.canvas.drawColor(Color.WHITE)
                        pdfPage.canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                        document.finishPage(pdfPage)
                        FileOutputStream(temp).use { document.writeTo(it) }
                    } finally {
                        document.close()
                        bitmap.recycle()
                    }
                    val entryName = safePageName(page.name, index) + ".pdf"
                    zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                    temp.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            toast("ZIP saved: ${zipFile.name} (${pages.size} individual PDFs; no combined PDF/JPEG)")
            buildHome()
        } catch (e: Exception) {
            toast("ZIP export failed: ${e.localizedMessage ?: "unknown error"}")
        } finally { tempFiles.forEach { it.delete() } }
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
                        }
                        .setNegativeButton("Save TXT") { _, _ ->
                            try {
                                val folder = outputFolder()
                                if (!folder.exists() && !folder.mkdirs()) {
                                    throw IllegalStateException("Could not create ScanPro folder")
                                }
                                val file = File(folder, "ScanPro_OCR_${System.currentTimeMillis()}.txt")
                                file.writeText(text, Charsets.UTF_8)
                                toast("OCR text saved: ${file.name}")
                            } catch (e: Exception) {
                                toast("Could not save text: ${e.localizedMessage ?: "unknown error"}")
                            }
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

    private fun optimizePdf(uri: Uri) {
        val tempInput = File(cacheDir, "scanpro_optimize_input_${System.currentTimeMillis()}.pdf")
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not read selected PDF")
            input.use { source -> FileOutputStream(tempInput).use { output -> source.copyTo(output) } }
            if (tempInput.length() == 0L) throw IllegalArgumentException("Selected PDF is empty")
            PDDocument.load(tempInput).use { document ->
                val folder = outputFolder()
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IllegalStateException("Could not create ScanPro folder")
                }
                val file = File(folder, "ScanPro_Optimized_${System.currentTimeMillis()}.pdf")
                document.save(file)
                val originalSize = tempInput.length()
                val newSize = file.length()
                val difference = originalSize - newSize
                val message = when {
                    difference > 0L -> "Size reduced by ${formatFileSize(difference)} (${(difference * 100 / originalSize)}%)."
                    difference < 0L -> "The rewritten PDF is ${formatFileSize(-difference)} larger. This PDF may already be optimized."
                    else -> "The file size stayed the same."
                }
                AlertDialog.Builder(this)
                    .setTitle("PDF optimization complete")
                    .setMessage("Original: ${formatFileSize(originalSize)}\nNew file: ${formatFileSize(newSize)}\n\n$message\n\nOriginal file was kept unchanged.")
                    .setPositiveButton("Done") { _, _ -> buildHome() }
                    .setNeutralButton("Open new PDF") { _, _ -> openPdf(file) }
                    .show()
            }
        } catch (e: Exception) {
            toast("PDF optimization failed: ${e.localizedMessage ?: "unsupported or damaged PDF"}")
        } finally {
            tempInput.delete()
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024f * 1024f))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
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
