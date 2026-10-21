package com.scanpro.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Best-effort document boundary detection and perspective correction.
 * If no confident four-corner page is found, the original capture is kept.
 */
object DocumentImageProcessor {
    @Volatile private var ready = false

    @Synchronized
    private fun ensureOpenCv(): Boolean {
        if (!ready) ready = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
        return ready
    }

    fun process(input: File): File {
        if (!ensureOpenCv()) return input
        val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return input
        var source: Mat? = null
        var gray: Mat? = null
        var edges: Mat? = null
        val contours = mutableListOf<MatOfPoint>()
        try {
            source = Mat()
            Utils.bitmapToMat(bitmap, source)
            gray = Mat()
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            edges = Mat()
            Imgproc.Canny(gray, edges, 55.0, 160.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            val imageArea = source.rows().toDouble() * source.cols().toDouble()
            var best: MatOfPoint2f? = null
            var bestArea = imageArea * 0.12
            for (contour in contours) {
                val curve = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(curve, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, perimeter * 0.02, true)
                val points = approx.toArray()
                val area = kotlin.math.abs(Imgproc.contourArea(MatOfPoint(*points)))
                if (points.size == 4 && area > bestArea && Imgproc.isContourConvex(MatOfPoint(*points))) {
                    best?.release()
                    best = approx
                    bestArea = area
                } else {
                    approx.release()
                }
                curve.release()
            }
            val corners = best?.toArray()
            best?.release()
            if (corners == null || corners.size != 4) return input
            val ordered = orderCorners(corners)
            val widthA = distance(ordered[2], ordered[3])
            val widthB = distance(ordered[1], ordered[0])
            val heightA = distance(ordered[1], ordered[2])
            val heightB = distance(ordered[0], ordered[3])
            val outWidth = max(widthA, widthB).toInt().coerceIn(300, 5000)
            val outHeight = max(heightA, heightB).toInt().coerceIn(300, 7000)
            if (outWidth < 300 || outHeight < 300) return input
            val from = MatOfPoint2f(ordered[0], ordered[1], ordered[2], ordered[3])
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(outWidth - 1.0, 0.0),
                Point(outWidth - 1.0, outHeight - 1.0), Point(0.0, outHeight - 1.0)
            )
            val transform = Imgproc.getPerspectiveTransform(from, to)
            val warped = Mat()
            Imgproc.warpPerspective(source, warped, transform, Size(outWidth.toDouble(), outHeight.toDouble()))
            val resultBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, resultBitmap)
            val corrected = File(input.parentFile, input.nameWithoutExtension + "_corrected.jpg")
            FileOutputStream(corrected).use { resultBitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            resultBitmap.recycle()
            from.release(); to.release(); transform.release(); warped.release()
            return if (corrected.length() > 0L) corrected else input
        } catch (_: Throwable) {
            return input
        } finally {
            bitmap.recycle()
            source?.release()
            gray?.release()
            edges?.release()
            contours.forEach { it.release() }
        }
    }

    private fun orderCorners(points: Array<Point>): Array<Point> {
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.x - it.y }
        val topLeft = points[sums.indices.minByOrNull { sums[it] }!!]
        val bottomRight = points[sums.indices.maxByOrNull { sums[it] }!!]
        val topRight = points[diffs.indices.maxByOrNull { diffs[it] }!!]
        val bottomLeft = points[diffs.indices.minByOrNull { diffs[it] }!!]
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
