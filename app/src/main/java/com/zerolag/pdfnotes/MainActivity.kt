package com.zerolag.pdfnotes

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private lateinit var pdfEngine: PdfRenderEngine
    private var canvasView: DrawingCanvasView? = null

    private val openPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { loadPdf(it) }
    }

    private var totalPages by mutableIntStateOf(0)
    private var currentPageIndex by mutableIntStateOf(0)
    private var canUndo by mutableStateOf(false)
    private var canRedo by mutableStateOf(false)

    // En az 10 farklı belirgin renk paleti
    private val paletteColors = listOf(
        AndroidColor.BLACK,
        AndroidColor.parseColor("#1E3A8A"), // Koyu Gece Mavisi
        AndroidColor.parseColor("#2563EB"), // Kobalt Mavisi
        AndroidColor.parseColor("#0284C7"), // Gök Mavisi
        AndroidColor.parseColor("#059669"), // Zümrüt Yeşili
        AndroidColor.parseColor("#16A34A"), // Açık Canlı Yeşil
        AndroidColor.parseColor("#DC2626"), // Kırmızı
        AndroidColor.parseColor("#EA580C"), // Turuncu
        AndroidColor.parseColor("#F59E0B"), // Kehribar Sarısı
        AndroidColor.parseColor("#7C3AED"), // Asil Mor
        AndroidColor.parseColor("#DB2777"), // Fuşya Pembe
        AndroidColor.parseColor("#78350F"), // Sıcak Kahverengi
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfEngine = PdfRenderEngine(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Color(0xFF3B82F6))
            ) {
                var activeTool by remember { mutableStateOf(ToolMode.PEN) }
                var activeColor by remember { mutableStateOf(AndroidColor.BLACK) }
                var showColorPicker by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF18181B))) {
                    // 1. Donanım Hızlandırmalı Çizim Tuvali
                    AndroidView(
                        factory = { context ->
                            DrawingCanvasView(context).also { view ->
                                canvasView = view
                                view.strokeColor = activeColor
                                view.onHistoryChanged = { u, r ->
                                    canUndo = u
                                    canRedo = r
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 2. ÜST KISMA ALINAN ARAÇ ÇUBUĞU (Kalem, Vurgulayıcı, Silgi, Kement, Renk Paleti)
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xF018181B),
                            modifier = Modifier
                                .shadow(12.dp, RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "PDF Aç", tint = Color.White)
                                }
                                IconButton(onClick = {
                                    activeTool = ToolMode.PEN
                                    canvasView?.currentMode = ToolMode.PEN
                                    canvasView?.strokeColor = activeColor
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Kalem", tint = if (activeTool == ToolMode.PEN) Color(0xFF3B82F6) else Color.White)
                                }
                                IconButton(onClick = {
                                    activeTool = ToolMode.HIGHLIGHTER
                                    canvasView?.currentMode = ToolMode.HIGHLIGHTER
                                    canvasView?.strokeColor = AndroidColor.parseColor("#F59E0B")
                                }) {
                                    Icon(Icons.Default.Brush, contentDescription = "Vurgulayıcı", tint = if (activeTool == ToolMode.HIGHLIGHTER) Color.Yellow else Color.White)
                                }
                                IconButton(onClick = {
                                    activeTool = ToolMode.ERASER_STROKE
                                    canvasView?.currentMode = ToolMode.ERASER_STROKE
                                }) {
                                    Icon(Icons.Default.AutoFixNormal, contentDescription = "Silgi", tint = if (activeTool == ToolMode.ERASER_STROKE) Color(0xFFEF4444) else Color.White)
                                }
                                IconButton(onClick = {
                                    activeTool = ToolMode.LASSO
                                    canvasView?.currentMode = ToolMode.LASSO
                                }) {
                                    Icon(Icons.Default.CropFree, contentDescription = "Kement", tint = if (activeTool == ToolMode.LASSO) Color(0xFF6366F1) else Color.White)
                                }

                                // 12 Renkli Palet Tetikleyici
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeColor))
                                        .border(2.dp, Color.White, CircleShape)
                                        .clickable { showColorPicker = !showColorPicker }
                                )

                                IconButton(onClick = { canvasView?.undo() }, enabled = canUndo) {
                                    Icon(Icons.Default.Undo, contentDescription = "Geri Al", tint = if (canUndo) Color.White else Color.Gray)
                                }
                                IconButton(onClick = { canvasView?.redo() }, enabled = canRedo) {
                                    Icon(Icons.Default.Redo, contentDescription = "Yinele", tint = if (canRedo) Color.White else Color.Gray)
                                }
                            }
                        }

                        // Renk Paleti Menüsü (Üst Çubuğun Altında)
                        if (showColorPicker) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xEE27272A),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .shadow(8.dp, RoundedCornerShape(16.dp))
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    paletteColors.forEach { colorInt ->
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(Color(colorInt))
                                                .border(
                                                    width = if (activeColor == colorInt) 2.5.dp else 1.dp,
                                                    color = if (activeColor == colorInt) Color.White else Color(0x44FFFFFF),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    activeColor = colorInt
                                                    canvasView?.strokeColor = colorInt
                                                    showColorPicker = false
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadPdf(uri: Uri) {
        totalPages = pdfEngine.openPdf(uri)
        currentPageIndex = 0
        if (totalPages > 0) {
            canvasView?.post {
                val bmp = pdfEngine.renderPage(0, canvasView!!.width, canvasView!!.height)
                canvasView?.pdfBackground = bmp
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfEngine.close()
    }
}