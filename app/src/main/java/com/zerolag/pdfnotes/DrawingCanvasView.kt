package com.zerolag.pdfnotes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

enum class ToolMode { PEN, HIGHLIGHTER, ERASER_STROKE, ERASER_POINT, LASSO }

data class Stroke(
    val path: Path,
    val color: Int,
    val strokeWidth: Float,
    val alpha: Int,
    val isHighlighter: Boolean = false,
    val bounds: RectF = RectF().apply { path.computeBounds(this, true) },
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var isSelected: Boolean = false
)

class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentMode: ToolMode = ToolMode.PEN
    var strokeColor: Int = Color.BLACK
    var baseStrokeWidth: Float = 4.5f

    private val strokes = ArrayList<Stroke>(128)
    private val undoStack = ArrayList<Stroke>(64)
    private val selectedStrokes = ArrayList<Stroke>()
    private var isDraggingSelection = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    // Zero-GC Preallocated Pools
    private val activePath = Path()
    private val activePaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val cachedRenderPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val highlighterXfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private val lassoPaint = Paint().apply {
        color = Color.parseColor("#2563EB")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val selectionBoundingPaint = Paint().apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val tempBounds = RectF()
    private val selectionBounds = RectF()
    private val clipRegion = Region()
    private val lassoRegion = Region()
    private val strokeRegion = Region()
    private val transformMatrix = Matrix()

    private var lastX = 0f
    private var lastY = 0f
    private var isDrawing = false

    // Palm Rejection state
    private var lastStylusTimestamp = 0L
    private val STYLUS_HOVER_TOUCH_COOLDOWN_MS = 400L
    private val PALM_TOUCH_MAJOR_THRESHOLD = 60f
    private val TOUCH_SLOP = 1.5f

    var pdfBackground: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusTimestamp = SystemClock.uptimeMillis()
        }
        return super.onHoverEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val now = SystemClock.uptimeMillis()

        // Palm rejection
        if (isStylus) {
            lastStylusTimestamp = now
        } else {
            if (now - lastStylusTimestamp < STYLUS_HOVER_TOUCH_COOLDOWN_MS) return true
            if (event.getTouchMajor(0) > PALM_TOUCH_MAJOR_THRESHOLD) return true
        }

        val activeTool = if (toolType == MotionEvent.TOOL_TYPE_ERASER) ToolMode.ERASER_STROKE else currentMode
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (activeTool == ToolMode.LASSO && selectedStrokes.isNotEmpty() && selectionBounds.contains(x, y)) {
                    isDraggingSelection = true
                    dragStartX = x
                    dragStartY = y
                    return true
                }
                if (selectedStrokes.isNotEmpty()) {
                    clearSelection()
                    invalidate()
                }

                isDrawing = true
                lastX = x
                lastY = y
                activePath.reset()
                activePath.moveTo(x, y)

                if (activeTool == ToolMode.ERASER_STROKE) {
                    eraseStrokeAt(x, y)
                    return true
                }

                configureActivePaint(activeTool, event.pressure)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    val dx = x - dragStartX
                    val dy = y - dragStartY
                    dragStartX = x
                    dragStartY = y
                    for (i in 0 until selectedStrokes.size) {
                        val stroke = selectedStrokes[i]
                        stroke.offsetX += dx
                        stroke.offsetY += dy
                        stroke.bounds.offset(dx, dy)
                    }
                    selectionBounds.offset(dx, dy)
                    invalidate()
                    return true
                }

                if (!isDrawing) return true
                if (activeTool == ToolMode.ERASER_STROKE) {
                    eraseStrokeAt(x, y)
                    return true
                }

                // Process historical hardware samples
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    appendBezierSample(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h), activeTool)
                }
                appendBezierSample(x, y, event.pressure, activeTool)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingSelection) {
                    isDraggingSelection = false
                    commitSelectionTransformation()
                    invalidate()
                    return true
                }
                if (!isDrawing) return true
                isDrawing = false

                if (activeTool == ToolMode.LASSO) {
                    evaluateLassoSelection()
                } else if (activeTool != ToolMode.ERASER_STROKE) {
                    val committedPath = Path(activePath)
                    val isHighlighter = activeTool == ToolMode.HIGHLIGHTER
                    val stroke = Stroke(
                        path = committedPath,
                        color = strokeColor,
                        strokeWidth = activePaint.strokeWidth,
                        alpha = if (isHighlighter) 115 else 255,
                        isHighlighter = isHighlighter
                    )
                    strokes.add(stroke)
                    undoStack.clear()
                    notifyHistoryState()
                }
                activePath.reset()
                invalidate()
            }
        }
        return true
    }

    private fun appendBezierSample(x: Float, y: Float, pressure: Float, tool: ToolMode) {
        val dx = Math.abs(x - lastX)
        val dy = Math.abs(y - lastY)
        if (dx >= TOUCH_SLOP || dy >= TOUCH_SLOP) {
            val midX = (x + lastX) * 0.5f
            val midY = (y + lastY) * 0.5f
            activePath.quadTo(lastX, lastY, midX, midY)
            lastX = x
            lastY = y

            if (tool == ToolMode.PEN) {
                val clampedPressure = pressure.coerceIn(0.05f, 1.0f)
                activePaint.strokeWidth = baseStrokeWidth * (0.5f + clampedPressure * 1.5f)
            }
        }
    }

    private fun configureActivePaint(tool: ToolMode, pressure: Float) {
        activePaint.reset()
        activePaint.isAntiAlias = true
        activePaint.style = Paint.Style.STROKE
        activePaint.strokeJoin = Paint.Join.ROUND
        activePaint.strokeCap = Paint.Cap.ROUND

        when (tool) {
            ToolMode.PEN -> {
                activePaint.color = strokeColor
                activePaint.alpha = 255
                val clampedPressure = pressure.coerceIn(0.05f, 1.0f)
                activePaint.strokeWidth = baseStrokeWidth * (0.5f + clampedPressure * 1.5f)
            }
            ToolMode.HIGHLIGHTER -> {
                activePaint.color = strokeColor
                activePaint.alpha = 115
                activePaint.strokeWidth = baseStrokeWidth * 3.8f
                activePaint.xfermode = highlighterXfermode
            }
            ToolMode.LASSO -> {
                activePaint.set(lassoPaint)
            }
            else -> {}
        }
    }

    private fun eraseStrokeAt(x: Float, y: Float) {
        for (i in strokes.indices.reversed()) {
            val stroke = strokes[i]
            tempBounds.set(stroke.bounds)
            tempBounds.offset(stroke.offsetX, stroke.offsetY)
            tempBounds.inset(-24f, -24f)

            if (tempBounds.contains(x, y)) {
                undoStack.add(strokes.removeAt(i))
                notifyHistoryState()
                invalidate()
                break
            }
        }
    }

    private fun evaluateLassoSelection() {
        activePath.computeBounds(tempBounds, true)
        clipRegion.set(tempBounds.left.toInt(), tempBounds.top.toInt(), tempBounds.right.toInt(), tempBounds.bottom.toInt())
        lassoRegion.setPath(activePath, clipRegion)

        selectedStrokes.clear()
        selectionBounds.setEmpty()

        for (i in 0 until strokes.size) {
            val stroke = strokes[i]
            tempBounds.set(stroke.bounds)
            tempBounds.offset(stroke.offsetX, stroke.offsetY)
            clipRegion.set(tempBounds.left.toInt(), tempBounds.top.toInt(), tempBounds.right.toInt(), tempBounds.bottom.toInt())
            strokeRegion.setPath(stroke.path, clipRegion)

            if (!lassoRegion.op(strokeRegion, Region.Op.INTERSECT)) {
                stroke.isSelected = true
                selectedStrokes.add(stroke)
                if (selectionBounds.isEmpty) selectionBounds.set(tempBounds) else selectionBounds.union(tempBounds)
            }
        }
        if (selectedStrokes.isNotEmpty()) selectionBounds.inset(-12f, -12f)
    }

    private fun commitSelectionTransformation() {
        for (i in 0 until selectedStrokes.size) {
            val stroke = selectedStrokes[i]
            if (stroke.offsetX != 0f || stroke.offsetY != 0f) {
                transformMatrix.reset()
                transformMatrix.setTranslate(stroke.offsetX, stroke.offsetY)
                stroke.path.transform(transformMatrix)
                stroke.path.computeBounds(stroke.bounds, true)
                stroke.offsetX = 0f
                stroke.offsetY = 0f
            }
        }
    }

    fun clearSelection() {
        for (i in 0 until selectedStrokes.size) selectedStrokes[i].isSelected = false
        selectedStrokes.clear()
        selectionBounds.setEmpty()
        isDraggingSelection = false
    }

    fun undo(): Boolean {
        if (strokes.isNotEmpty()) {
            undoStack.add(strokes.removeAt(strokes.size - 1))
            clearSelection()
            notifyHistoryState()
            invalidate()
            return true
        }
        return false
    }

    fun redo(): Boolean {
        if (undoStack.isNotEmpty()) {
            strokes.add(undoStack.removeAt(undoStack.size - 1))
            clearSelection()
            notifyHistoryState()
            invalidate()
            return true
        }
        return false
    }

    private fun notifyHistoryState() {
        onHistoryChanged?.invoke(strokes.isNotEmpty(), undoStack.isNotEmpty())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pdfBackground?.let { bg -> if (!bg.isRecycled) canvas.drawBitmap(bg, 0f, 0f, null) }

        for (i in 0 until strokes.size) {
            val stroke = strokes[i]
            cachedRenderPaint.reset()
            cachedRenderPaint.isAntiAlias = true
            cachedRenderPaint.style = Paint.Style.STROKE
            cachedRenderPaint.strokeJoin = Paint.Join.ROUND
            cachedRenderPaint.strokeCap = Paint.Cap.ROUND
            cachedRenderPaint.color = stroke.color
            cachedRenderPaint.alpha = stroke.alpha
            cachedRenderPaint.strokeWidth = stroke.strokeWidth
            if (stroke.isHighlighter) cachedRenderPaint.xfermode = highlighterXfermode

            if (stroke.offsetX != 0f || stroke.offsetY != 0f) {
                val sc = canvas.save()
                canvas.translate(stroke.offsetX, stroke.offsetY)
                canvas.drawPath(stroke.path, cachedRenderPaint)
                canvas.restoreToCount(sc)
            } else {
                canvas.drawPath(stroke.path, cachedRenderPaint)
            }
        }

        if (isDrawing) canvas.drawPath(activePath, activePaint)

        if (selectedStrokes.isNotEmpty() && !selectionBounds.isEmpty) {
            canvas.drawRect(selectionBounds, selectionBoundingPaint)
        }
    }
}