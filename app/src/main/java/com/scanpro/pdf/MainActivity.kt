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
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val blue = Color.rgb(37, 99, 235)
    private val ink = Color.rgb(20, 31, 55)
    private val muted = Color.rgb(102, 112, 133)
    private val selectedImages = mutableListOf<Uri>()

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
        buildHome()
    }

    private fun buildHome() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 252))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        scroll.addView(page)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = rounded(Color.rgb(24, 67, 160), 24)
        }
        hero.addView(TextView(this).apply {
            text = "SCANPRO  PDF"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(191, 219, 254))
            typeface = Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "Paperwork,\nmade simple."
            textSize = 30f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(8))
        })
        hero.addView(TextView(this).apply {
            text = "Scan documents, make PDFs and extract text — all on your phone."
            textSize = 14f
            setTextColor(Color.rgb(219, 234, 254))
        })
        page.addView(hero, matchWrap().apply { bottomMargin = dp(24) })

        sectionTitle(page, "QUICK ACTIONS")
        val cameraCard = addAction(
            page, "📷", "Scan document", "Auto-crop, enhance and scan multiple pages", "START SCANNING"
        ) { launchDocumentScanner() }
        val imageCard = addAction(
            page, "🖼️", "Images to PDF", "Combine multiple pictures into one PDF", "CHOOSE IMAGES"
        ) { imagePicker.launch("image/*") }
        addAction(
            page, "🔎", "Text recognition (OCR)", "Extract printed text from an image", "EXTRACT TEXT"
        ) { ocrPicker.launch("image/*") }

        sectionTitle(page, "YOUR DOCUMENTS")
        val saved = outputFolder().listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val summary = TextView(this).apply {
            text = if (saved.isEmpty()) "No PDFs yet. Scan a page or choose images to get started."
                else "${saved.size} PDF file(s) saved on this device"
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(10))
        }
        page.addView(summary)
        if (saved.isEmpty()) {
            val empty = TextView(this).apply {
                text = "📄\n\nYour recent PDFs will appear here"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(18), dp(24), dp(18), dp(24))
                background = rounded(Color.WHITE, 18)
            }
            page.addView(empty, matchWrap().apply { bottomMargin = dp(12) })
        } else {
            saved.take(8).forEach { pdf ->
                addPdfRow(page, pdf)
            }
            if (saved.size > 8) {
                addTextButton(page, "View all ${saved.size} PDFs") { showAllPdfs() }
            }
        }

        page.addView(TextView(this).apply {
            text = "🔒  Your files stay on your device. OCR runs on-device."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(22), 0, 0)
        })
        setContentView(scroll)
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
            background = rounded(Color.WHITE, 18)
            elevation = dp(2).toFloat()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = icon
            textSize = 25f
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(239, 244, 255), 14)
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
            background = rounded(Color.WHITE, 15)
            setOnClickListener { showPdfOptions(pdf) }
        }
        row.addView(TextView(this).apply {
            text = "📄"
            textSize = 23f
            setPadding(0, 0, dp(10), 0)
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
        if (files.isEmpty()) {
            toast("No saved PDFs yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Saved PDFs")
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> showPdfOptions(files[which]) }
            .setNegativeButton("Close", null)
            .show()
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
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open PDF with"))
        } catch (_: android.content.ActivityNotFoundException) {
            toast("No PDF viewer found. Install a PDF reader first.")
        } catch (e: Exception) {
            toast("Could not open PDF: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun sharePdf(pdf: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdf)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (e: Exception) {
            toast("Could not share PDF: ${e.localizedMessage ?: "unknown error"}")
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
        val options = GmsDocumentScanningOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(
                GmsDocumentScanningOptions.RESULT_FORMAT_PDF,
                GmsDocumentScanningOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScanningOptions.SCANNER_MODE_FULL)
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
