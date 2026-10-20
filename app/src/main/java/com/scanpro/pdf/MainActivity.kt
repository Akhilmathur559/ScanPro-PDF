package com.scanpro.pdf

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val selectedImages = mutableListOf<Uri>()
    private var cameraBitmap: Bitmap? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages.clear()
            selectedImages.addAll(uris)
            Toast.makeText(this, "${uris.size} image(s) selected", Toast.LENGTH_SHORT).show()
            createPdfFromUris(uris)
        }
    }

    private val ocrPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) runOcr(uri)
    }

    private val cameraPicker = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            cameraBitmap = bitmap
            createPdfFromBitmaps(listOf(bitmap))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHome()
    }

    private fun buildHome() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(24))
            setBackgroundColor(Color.rgb(246, 248, 252))
        }
        val scroll = ScrollView(this)
        scroll.addView(page)

        page.addView(TextView(this).apply {
            text = "ScanPro PDF"
            textSize = 30f
            setTextColor(Color.rgb(21, 94, 239))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        page.addView(TextView(this).apply {
            text = "Scan documents • Create PDFs • Extract text"
            textSize = 14f
            setTextColor(Color.rgb(71, 84, 103))
            setPadding(0, dp(6), 0, dp(20))
        })

        addAction(page, "📷  Scan with camera", "Capture a document image and save it as PDF") {
            cameraPicker.launch(null)
        }
        addAction(page, "🖼  Images to PDF", "Select multiple images and combine them into one PDF") {
            imagePicker.launch("image/*")
        }
        addAction(page, "🔎  Extract text (OCR)", "Recognize printed text from an image") {
            ocrPicker.launch("image/*")
        }
        addAction(page, "📚  Merge / Split PDFs", "PDF merge and page splitting are planned for the next build") {
            AlertDialog.Builder(this)
                .setTitle("PDF tools")
                .setMessage("Merge and split tools are not included in this starter build yet. The next development step is adding page selection and PDF combining.")
                .setPositiveButton("OK", null)
                .show()
        }
        addAction(page, "📂  Saved PDFs", "Open the folder where ScanPro stores created PDFs") {
            val folder = outputFolder()
            Toast.makeText(this, "Saved in: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
        }
        page.addView(TextView(this).apply {
            text = "Your files stay on your device. OCR runs on-device."
            textSize = 12f
            setTextColor(Color.rgb(102, 112, 133))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(scroll)
    }

    private fun addAction(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.rgb(16, 24, 40))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Color.rgb(71, 84, 103))
            setPadding(0, dp(5), 0, dp(10))
        })
        card.addView(Button(this).apply {
            text = "Open"
            isAllCaps = false
            setOnClickListener { action() }
        })
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
        parent.addView(card, params)
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
                page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), paint)
                document.finishPage(page)
            }
            val folder = outputFolder()
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "ScanPro_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { document.writeTo(it) }
            toast("PDF saved: ${file.name}")
        } catch (e: Exception) {
            toast("Could not save PDF: ${e.localizedMessage ?: "unknown error"}")
        } finally {
            document.close()
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
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Recognized text", text))
                            toast("Text copied")
                        }
                        .show()
                }
                .addOnFailureListener { e -> toast("OCR failed: ${e.localizedMessage ?: "unknown error"}") }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Exception) {
            toast("Could not open image: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun outputFolder(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "ScanPro")

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
