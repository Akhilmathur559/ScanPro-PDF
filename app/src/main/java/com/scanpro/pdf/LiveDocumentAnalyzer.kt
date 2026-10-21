package com.scanpro.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Lightweight live document-edge detector. Coordinates are normalized to 0..1.
 * It only draws a guide; the captured full-resolution photo is corrected separately.
 */
class LiveDocumentAnalyzer(
    private val onCorners: (List<Pair<Float, Float>>?) -> Unit
) : ImageAnalysis.Analyzer {
    private var frameCount = 0
    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            frameCount++
            if (frameCount % 3 != 0 || !OpenCVLoader.initLocal()) {
                image.close()
                return
            }
            val plane = image.planes.firstOrNull() ?: return
            val buffer: ByteBuffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val mat = Mat(image.height, image.width, org.opencv.core.CvType.CV_8UC1)
            mat.put(0, 0, bytes)
            val small = Mat()
            val gray = Mat()
            val edges = Mat()
            val contours = ArrayList<MatOfPoint>()
            try {
                val scale = 640.0 / maxOf(mat.cols(), mat.rows()).toDouble()
                Imgproc.resize(mat, small, org.opencv.core.Size(mat.cols() * scale, mat.rows() * scale))
                Imgproc.GaussianBlur(small, gray, org.opencv.core.Size(5.0, 5.0), 0.0)
                Imgproc.Canny(gray, edges, 55.0, 150.0)
                val hierarchy = Mat()
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()
                val frameArea = small.rows().toDouble() * small.cols().toDouble()
                var best: Array<Point>? = null
                var bestArea = frameArea * 0.12
                for (contour in contours) {
                    val curve = MatOfPoint2f(*contour.toArray())
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.025, true)
                    val pts = approx.toArray()
                    if (pts.size == 4) {
                        val polygon = MatOfPoint(*pts)
                        val area = abs(Imgproc.contourArea(polygon))
                        if (area > bestArea && Imgproc.isContourConvex(polygon)) {
                            best = pts
                            bestArea = area
                        }
                        polygon.release()
                    }
                    curve.release()
                    approx.release()
                }
                val normalized = best?.map { p ->
                    Pair((p.x / small.cols()).toFloat().coerceIn(0f, 1f), (p.y / small.rows()).toFloat().coerceIn(0f, 1f))
                }
                onCorners(normalized)
            } finally {
                contours.forEach { it.release() }
                mat.release(); small.release(); gray.release(); edges.release()
            }
        } catch (_: Throwable) {
            onCorners(null)
        } finally {
            image.close()
            busy.set(false)
        }
    }
}

/** Transparent view that draws detected page corners over CameraX preview. */
class DocumentCornerOverlay(context: Context) : View(context) {
    @Volatile private var corners: List<Pair<Float, Float>>? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 211, 153)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 211, 153)
        style = Paint.Style.FILL
    }

    fun updateCorners(value: List<Pair<Float, Float>>?) {
        corners = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = corners ?: return
        if (pts.size != 4) return
        val ordered = order(pts)
        val path = android.graphics.Path()
        path.moveTo(ordered[0].first * width, ordered[0].second * height)
        for (i in 1..3) path.lineTo(ordered[i].first * width, ordered[i].second * height)
        path.close()
        canvas.drawPath(path, line)
        ordered.forEach { canvas.drawCircle(it.first * width, it.second * height, 8f, dot) }
    }

    private fun order(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val tl = points.minByOrNull { it.first + it.second }!!
        val br = points.maxByOrNull { it.first + it.second }!!
        val tr = points.maxByOrNull { it.first - it.second }!!
        val bl = points.minByOrNull { it.first - it.second }!!
        return listOf(tl, tr, br, bl)
    }
}
