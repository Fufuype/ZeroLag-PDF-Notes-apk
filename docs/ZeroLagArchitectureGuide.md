# ZeroLag Notes: Low-Level Graphics & Stylus Optimization Guide

## Target Hardware
- **Target Devices**: Budget Lenovo Android Tablets (Lenovo Tab M8, Tab M10 Plus, Tab P11 Gen 1/2) with 2GB–4GB RAM and MediaTek Helio / Qualcomm Snapdragon 600-series SoCs.
- **Input Hardware**: Active Electrostatic (AES) Stylus (Lenovo Precision Pen 2, Active Pen 3) supporting 4,096 pressure levels and tilt.

---

## 1. Zero Garbage Collection (GC) Architecture in the Touch/Render Pipeline
On low-end Android devices with 2GB RAM, the Dalvik/ART GC pauses (Concurrent Mark-Sweep or Generational Garbage Collection) cause micro-stutters ("jank") lasting 8ms–32ms. If an object is allocated in onTouchEvent or onDraw, drawing a rapid curve at 120Hz creates ~10,000 temporary objects per second, immediately triggering GC pressure.

### Our Strategy:
1. **Pre-allocated Object Pools**: activePath, activePaint, cachedRenderPaint, tempBounds, selectionBounds, clipRegion, lassoRegion, strokeRegion, and transformMatrix are instantiated once in init and reused throughout the app's lifecycle.
2. **Zero Iterator Allocation**: for (i in 0 until strokes.size) is strictly enforced over for (stroke in strokes). Standard Kotlin Iterable loops create an ephemeral Iterator object on each frame; index-based looping compiles to a raw direct array lookup with 0 heap allocations.
3. **No String/Boxing Concatenation** in hot loops.

---

## 2. Advanced Palm Rejection: The 3-Tier Defense System
Lenovo budget tablets often produce false palm touches because the touch digitizer scanning rate (120Hz–240Hz) is decoupled from the screen refresh rate (60Hz).

### Tier 1: Hardware Tool Differentiation
Android's MotionEvent.getToolType(pointerIndex) reports:
- TOOL_TYPE_STYLUS: Pen tip contact.
- TOOL_TYPE_ERASER: Stylus barrel button / secondary tip.
- TOOL_TYPE_FINGER: Capacitive finger or palm contact.

### Tier 2: The 400ms Post-Stylus Hover/Touch Window
When a user writes, their palm rests on the glass surface while the pen tip occasionally lifts 1mm–3mm between letters. Without an intelligent cooldown window, the palm registers as a sudden finger drag, leaving blotches.
We update lastStylusTimestamp = SystemClock.uptimeMillis() on:
- onHoverEvent (when pen is hovering within 12mm of the screen).
- onTouchEvent with TOOL_TYPE_STYLUS.
Any incoming TOOL_TYPE_FINGER event occurring within 400ms of lastStylusTimestamp is rejected and consumed immediately (return true).

### Tier 3: Capacitive Contact Area Major Axis Filtering
When a resting palm touches the digitizer without the stylus nearby, MotionEvent.getTouchMajor(0) returns the length of the major axis of the contact ellipse. A normal fingertip measures 15f–30f, whereas a palm contact patch exceeds 60f. Any touch event with touchMajor > 60f is discarded.

---

## 3. High-Frequency Sampling via event.historySize
Active styluses like the Lenovo Precision Pen sample at up to 240Hz, while Android UI frames render at 60Hz or 90Hz. Android buffers the intermediate hardware samples inside MotionEvent.
If an app only reads event.x and event.y, it drops up to 75% of coordinate samples, creating jagged, polygonal strokes.
We iterate through 0 until event.historySize calling event.getHistoricalX(h), event.getHistoricalY(h), and event.getHistoricalPressure(h).

---

## 4. Bezier Quadratic (quadTo) Interpolation
Raw digitizer coordinates contain high-frequency jitter. Connecting them with straight lineTo creates sharp micro-angles.
Instead, we compute midpoints between consecutive points:
```kotlin
val midX = (x + lastX) * 0.5f
val midY = (y + lastY) * 0.5f
activePath.quadTo(lastX, lastY, midX, midY)
```
This guarantees C1 continuity (tangent continuity) at every knot, resulting in calligraphic vector curves.

---

## 5. Offline PDF Processing via android.graphics.pdf.PdfRenderer
- **Zero Third-Party Libraries**: Avoids 15MB+ heavy SDKs like PDFBox, iText, or MuPDF.
- **Native OS C++ Pipeline**: PdfRenderer leverages Google's internal native Pdfium C++ engine baked into Android.
- **LruCache Bitmap Recycling**: Recycles memory-heavy bitmaps to ensure app memory footprint stays under 45MB.
- **Vector PDF Export**: Uses android.graphics.pdf.PdfDocument to write clean, crisp annotated PDFs directly to local storage.