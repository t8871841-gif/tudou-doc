package com.jarvis.native.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * 需求文档总原则里的「网格标定法」：给截图叠加标号网格（8 行 A-H × 12 列 1-12），
 * 模型只许回答「格子号 + 格内三分位置」，不许直接报原始坐标——不准的视觉模型报错像素坐标
 * 没法用，报“大概在哪个格子的哪一块”反而稳。这里负责：画网格图、把格子号+三分位置换算回
 * 真实屏幕坐标、以及“放大区域二次确认”的裁剪换算。
 */
object GridOverlay {
    const val ROWS = 8 // A..H
    const val COLS = 12 // 1..12

    data class CellSpec(val row: Int, val col: Int) {
        val label: String get() = "${('A' + row)}${col + 1}"
    }

    fun parseLabel(label: String): CellSpec? {
        val m = Regex("^([A-Ha-h])\\s*([1-9]|1[0-2])$").find(label.trim()) ?: return null
        val row = m.groupValues[1].uppercase()[0] - 'A'
        val col = m.groupValues[2].toInt() - 1
        if (row !in 0 until ROWS || col !in 0 until COLS) return null
        return CellSpec(row, col)
    }

    /** 在截图上叠加网格线 + 每格左上角的标号文字，返回新 Bitmap（不修改原图）。 */
    fun drawGrid(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val cellW = out.width.toFloat() / COLS
        val cellH = out.height.toFloat() / ROWS
        val linePaint = Paint().apply {
            color = Color.argb(160, 0, 230, 200)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.argb(230, 0, 230, 200)
            textSize = minOf(cellW, cellH) * 0.22f
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        for (i in 0..COLS) c.drawLine(i * cellW, 0f, i * cellW, out.height.toFloat(), linePaint)
        for (i in 0..ROWS) c.drawLine(0f, i * cellH, out.width.toFloat(), i * cellH, linePaint)
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                val label = CellSpec(r, col).label
                c.drawText(label, col * cellW + 4f, r * cellH + textPaint.textSize + 2f, textPaint)
            }
        }
        return out
    }

    /**
     * 格子号 + 三分位置（左/中/右）换算成"图片内坐标"（像素，图片自身的宽高 imgW×imgH——
     * 也就是发给视觉模型那张截图/放大图的像素坐标系，不是设备真实屏幕分辨率）。
     * 截图上传前通常会被压缩/限长边，所以图片坐标系和设备屏幕坐标系往往不是同一个尺度；
     * 调用方自己负责最后用 [imageToScreenPoint] 按压缩前后的缩放比换算回真实屏幕坐标再点击。
     */
    fun cellToScreenPoint(cell: CellSpec, sub: String, imgW: Int, imgH: Int): Pair<Float, Float> {
        val cellW = imgW.toFloat() / COLS
        val cellH = imgH.toFloat() / ROWS
        val xFrac = when (sub.lowercase()) {
            "left", "左" -> 1f / 6f
            "right", "右" -> 5f / 6f
            else -> 0.5f
        }
        val x = colX(cell.col, cellW, xFrac)
        val y = cell.row * cellH + cellH * 0.5f
        return x to y
    }

    private fun colX(col: Int, cellW: Float, xFrac: Float): Float = col * cellW + cellW * xFrac

    /** 图片坐标系里的一个点 → 设备真实屏幕坐标系（按截图压缩前后的宽高比换算）。 */
    fun imageToScreenPoint(x: Float, y: Float, imgW: Int, imgH: Int, sw: Int, sh: Int): Pair<Float, Float> {
        if (imgW <= 0 || imgH <= 0) return x to y
        return (x * sw / imgW) to (y * sh / imgH)
    }

    /**
     * 区域放大准星：以格子中心为基准裁出一块更大的区域（默认左右各扩 1 个格、上下各扩 1 个格），
     * 放大 zoomFactor 倍，并在当前最佳猜测点画一个十字准星，再次让模型在这张放大图里确认精确位置
     * （模型这次回答的是裁剪图内的相对比例 x_frac/y_frac，由 [fracToScreenPoint] 换算回"图片坐标"，
     * 最后还要再经 [imageToScreenPoint] 才是真实屏幕坐标）。
     * 裁剪坐标系与 [src] 自身的像素尺寸一致（即图片坐标系），不是设备屏幕分辨率。
     */
    fun zoomRegion(src: Bitmap, cell: CellSpec, zoomFactor: Int = 3): ZoomResult {
        val imgW = src.width
        val imgH = src.height
        val cellW = imgW.toFloat() / COLS
        val cellH = imgH.toFloat() / ROWS
        val cx = cell.col * cellW + cellW * 0.5f
        val cy = cell.row * cellH + cellH * 0.5f
        val halfW = cellW * 1.5f
        val halfH = cellH * 1.5f
        val left = (cx - halfW).coerceIn(0f, imgW.toFloat())
        val top = (cy - halfH).coerceIn(0f, imgH.toFloat())
        val right = (cx + halfW).coerceIn(0f, imgW.toFloat())
        val bottom = (cy + halfH).coerceIn(0f, imgH.toFloat())
        val rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        if (rect.width() <= 0 || rect.height() <= 0) {
            return ZoomResult(src, rect)
        }
        val cropped = Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())
        val scaled = Bitmap.createScaledBitmap(
            cropped, (rect.width() * zoomFactor).coerceAtMost(2000),
            (rect.height() * zoomFactor).coerceAtMost(2000), true
        )
        val marked = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(marked)
        val paint = Paint().apply {
            color = Color.argb(220, 255, 70, 70)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val mcx = marked.width / 2f
        val mcy = marked.height / 2f
        c.drawLine(mcx - 24f, mcy, mcx + 24f, mcy, paint)
        c.drawLine(mcx, mcy - 24f, mcx, mcy + 24f, paint)
        return ZoomResult(marked, rect)
    }

    data class ZoomResult(val bitmap: Bitmap, val sourceRect: Rect)

    /** 放大图内的相对比例坐标 → 图片坐标系里的点（配合 [zoomRegion] 的 sourceRect 使用；
     *  还要再经 [imageToScreenPoint] 才是真实屏幕坐标）。 */
    fun fracToImagePoint(sourceRect: Rect, xFrac: Float, yFrac: Float): Pair<Float, Float> {
        val x = sourceRect.left + sourceRect.width() * xFrac.coerceIn(0f, 1f)
        val y = sourceRect.top + sourceRect.height() * yFrac.coerceIn(0f, 1f)
        return x to y
    }

    fun toJpegBase64(bmp: Bitmap, quality: Int = 80): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
