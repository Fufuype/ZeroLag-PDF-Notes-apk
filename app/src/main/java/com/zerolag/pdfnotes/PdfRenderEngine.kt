package com.zerolag.pdfnotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * PdfRenderEngine: High-performance, zero-dependency PDF renderer.
 * Wraps Android's native android.graphics.pdf.PdfRenderer API.
 */
class PdfRenderEngine(private val context: Context) {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var openPage: PdfRenderer.Page? = null

    var pageCount: Int = 0
        private set

    // Cache up to 3 rendered page bitmaps in memory (prevents OOM on budget Lenovo tablets)
    private val pageBitmapCache = object : LruCache<Int, Bitmap>(3) {
        override fun entryRemoved(evicted: Boolean, key: Int?, oldValue: Bitmap?, newValue: Bitmap?) {}
    }

    @Synchronized
    fun openPdf(uri: Uri): Int {
        close()
        return try {
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            fileDescriptor?.let { pfd ->
                pdfRenderer = PdfRenderer(pfd).also { renderer ->
                    pageCount = renderer.pageCount
                }
            }
            pageCount
        } catch (e: Exception) {
            e.printStackTrace()
            close()
            0
        }
    }

    @Synchronized
    fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= pageCount || targetWidth <= 0 || targetHeight <= 0) return null

        pageBitmapCache.get(pageIndex)?.let { cached ->
            if (!cached.isRecycled && cached.width == targetWidth && cached.height == targetHeight) {
                return cached
            }
        }

        return try {
            openPage?.close()
            openPage = renderer.openPage(pageIndex)

            val page = openPage ?: return null
            val pageWidth = page.width
            val pageHeight = page.height

            val scale = minOf(
                targetWidth.toFloat() / pageWidth.toFloat(),
                targetHeight.toFloat() / pageHeight.toFloat()
            )

            val renderWidth = (pageWidth * scale).toInt().coerceAtLeast(1)
            val renderHeight = (pageHeight * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            pageBitmapCache.put(pageIndex, bitmap)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Throws(IOException::class)
    fun exportAnnotatedPdf(
        pageBitmapsWithAnnotations: List<Bitmap>,
        outputFile: File
    ) {
        val pdfDocument = PdfDocument()

        try {
            for (i in pageBitmapsWithAnnotations.indices) {
                val bitmap = pageBitmapsWithAnnotations[i]
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val canvas = page.canvas
                canvas.drawBitmap(bitmap, 0f, 0f, null)

                pdfDocument.finishPage(page)
            }

            FileOutputStream(outputFile).use { outStream ->
                pdfDocument.writeTo(outStream)
            }
        } finally {
            pdfDocument.close()
        }
    }

    @Synchronized
    fun close() {
        try { openPage?.close() } catch (ignored: Exception) {}
        openPage = null

        try { pdfRenderer?.close() } catch (ignored: Exception) {}
        pdfRenderer = null

        try { fileDescriptor?.close() } catch (ignored: Exception) {}
        fileDescriptor = null

        pageBitmapCache.evictAll()
        pageCount = 0
    }
}