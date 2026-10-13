package com.planphoto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 핀 좌표. 원점은 도면 좌하단, X는 오른쪽, Y는 위쪽 (CAD 방식).
 * paper = 도면 용지 위 mm.
 */
data class Coord(val paperX: Float, val paperY: Float) {
    val nameX get() = paperX
    val nameY get() = paperY

    fun paperText() = String.format(Locale.KOREA, "X %.1f mm   Y %.1f mm", paperX, paperY)

    /** 화면·사진 표기 */
    fun mainText() = "도면 " + paperText()

    /** 파일 이름 앞부분 */
    fun fileToken(): String {
        val x = Math.round(nameX).coerceIn(0, 9_999_999)
        val y = Math.round(nameY).coerceIn(0, 9_999_999)
        return "X%07d_Y%07d".format(x, y)
    }
}

private const val PT_TO_MM = 25.4f / 72f

object PhotoProcessor {

    /** 핀의 좌표를 기준 쪽 크기(포인트)로부터 계산 — PDF를 교체해도 같은 값 */
    fun coordOf(c: Context, plan: Plan, pin: Pin): Coord {
        val base = plan.baseSizes.getOrNull(pin.page)
            ?: PdfPages.withPage(c, plan, pin.page) { _, fit, _ -> Pair(fit.baseW, fit.baseH) }
            ?: Pair(0f, 0f)
        return Coord(pin.x * base.first * PT_TO_MM, (1f - pin.y) * base.second * PT_TO_MM)
    }

    /** 핀 주변 도면을 잘라 정사각형 비트맵으로 (핀이 가운데) */
    private fun renderPlanCrop(c: Context, plan: Plan, pin: Pin, sizePx: Int): Bitmap? =
        PdfPages.withPage(c, plan, pin.page) { page, fit, _ ->
            val side = min(fit.baseW, fit.baseH) * 0.30f      // 도면 짧은 변의 30%를 보여줌
            val cx = pin.x * fit.baseW
            val cy = pin.y * fit.baseH
            val k = sizePx / side
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, fit.matrix(k, -(cx - side / 2f) * k, -(cy - side / 2f) * k),
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }

    /** EXIF 회전을 반영해 긴 변 maxSide 이하로 디코드 */
    fun decodeRotated(f: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val deg = try {
            ExifInterface(f.absolutePath).rotationDegrees
        } catch (_: Exception) { 0 }
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /**
     * 원본 사진 아래에 정보 패널(도면 위치 확대, XY 좌표, 촬영 시각)을 붙인 좌표 사진을 만든다.
     * 원본 파일은 그대로 둔다.
     */
    fun process(c: Context, plan: Plan, pin: Pin, originalPath: String, time: Long, coord: Coord): Photo {
        val orig = Repo.photoFile(c, originalPath)
        val markedPath = Repo.markedPathFor(originalPath)
        val markedFile = Repo.photoFile(c, markedPath)

        val photo = decodeRotated(orig, 2400) ?: throw IllegalStateException("사진을 읽을 수 없습니다")
        val w = photo.width
        val base = min(w, photo.height)
        val panelH = (base * 0.42f).toInt()
        val pad = panelH * 0.08f
        val cropSide = (panelH - pad * 2).toInt()

        val out = Bitmap.createBitmap(w, photo.height + panelH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawBitmap(photo, 0f, 0f, null)
        photo.recycle()
        val top = out.height - panelH.toFloat()
        cv.drawRect(0f, top, w.toFloat(), out.height.toFloat(), Paint().apply { color = Color.rgb(0x1F, 0x23, 0x28) })

        // 도면 확대 + 핀
        var textLeft = pad
        renderPlanCrop(c, plan, pin, cropSide)?.let { crop ->
            val l = pad
            val t = top + pad
            cv.drawBitmap(crop, l, t, null)
            crop.recycle()
            val cx = l + cropSide / 2f
            val cy = t + cropSide / 2f
            val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 0xF2, 0x6B, 0x1D); strokeWidth = cropSide / 220f
            }
            cv.drawLine(l, cy, l + cropSide, cy, cross)
            cv.drawLine(cx, t, cx, t + cropSide, cross)
            pin.dir?.let { PinMarker.drawCone(cv, cx, cy, cropSide * 0.256f, it) }
            PinMarker.draw(cv, cx, cy, cropSide * 0.075f, pin.label, filled = true, count = 0)
            cv.drawRect(l, t, l + cropSide, t + cropSide, Paint().apply {
                style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = cropSide / 120f
            })
            textLeft = l + cropSide + pad
        }

        // 텍스트
        val avail = w - textLeft - pad
        val lines = mutableListOf<Triple<String, Int, Float>>() // 내용, 색, 상대 크기
        lines += Triple("핀 ${pin.label}", Color.rgb(0xF2, 0x6B, 0x1D), 1.25f)
        lines += Triple("도면 " + coord.paperText(), Color.WHITE, 1.1f)
        pin.dir?.let { lines += Triple("방향 " + PinMarker.dirText(it) + " (도면 위쪽 기준)", Color.WHITE, 0.9f) }
        lines += Triple(Repo.timeFormat.format(Date(time)), Color.WHITE, 1f)
        lines += Triple("${plan.name} · ${pin.page + 1}쪽 · 원점 도면 좌하단", Color.rgb(0xA3, 0xAA, 0xB3), 0.72f)

        val baseSize = panelH * 0.105f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 가장 긴 줄이 공간에 맞도록 전체 크기 조정
        var fit = 1f
        for ((txt, _, rel) in lines) {
            paint.textSize = baseSize * rel
            paint.typeface = Typeface.DEFAULT_BOLD
            val wTxt = paint.measureText(txt)
            if (wTxt > avail) fit = min(fit, avail / wTxt)
        }
        val gaps = baseSize * 0.42f * fit
        val total = lines.sumOf { (baseSize * it.third * fit).toDouble() }.toFloat() + gaps * (lines.size - 1)
        var y = top + (panelH - total) / 2f
        for ((i, line) in lines.withIndex()) {
            paint.textSize = baseSize * line.third * fit
            paint.color = line.second
            paint.typeface = if (i == 0 || line.third >= 1f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            y += paint.textSize
            cv.drawText(line.first, textLeft, y - paint.descent() * 0.3f, paint)
            y += gaps
        }

        markedFile.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()

        // 좌표 사진에도 촬영 시각 EXIF 기록
        try {
            ExifInterface(markedFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(time)))
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "PIN ${pin.label} ${coord.mainText()}")
                saveAttributes()
            }
        } catch (_: Exception) {
        }

        Repo.exportToGallery(c, orig, plan, pin, time)
        Repo.exportToGallery(c, markedFile, plan, pin, time)

        return Photo(originalPath, markedPath, time,
            coord.nameX, coord.nameY,
            coord.mainText() + (pin.dir?.let { " · 방향 " + PinMarker.dirText(it) } ?: ""))
    }
}

/** 도면 위 물방울 모양 핀. (tipX, tipY)가 핀 끝 = 실제 위치 */
object PinMarker {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    const val ORANGE = 0xFFF26B1D.toInt()
    const val INK = 0xFF1F2328.toInt()

    /** 원형 카메라 핀은 원의 가운데가 실제 위치 */
    fun headCenterY(tipY: Float, r: Float) = tipY

    private val cone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** 방향 표기: 45° → "45° (북동↗)" 처럼 도면 위쪽 기준 */
    fun dirText(dir: Float): String {
        val names = arrayOf("위↑", "오른쪽 위↗", "오른쪽→", "오른쪽 아래↘", "아래↓", "왼쪽 아래↙", "왼쪽←", "왼쪽 위↖")
        val i = (((dir % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8
        return "${Math.round(dir)}° ${names[i]}"
    }

    /**
     * 핀 끝에서 촬영 방향으로 펼쳐지는 시야 부채꼴(60°)과 방향 화살표.
     * dir: 도면 위쪽 0°, 시계 방향.
     */
    fun drawCone(cv: Canvas, tipX: Float, tipY: Float, radius: Float, dir: Float, active: Boolean = false) {
        val start = dir - 90f - 30f       // 캔버스 각도는 오른쪽이 0°
        val oval = RectF(tipX - radius, tipY - radius, tipX + radius, tipY + radius)
        cone.color = if (active) 0x662F80ED else 0x55F26B1D
        cv.drawArc(oval, start, 60f, true, cone)
        coneEdge.color = if (active) 0xFF2F80ED.toInt() else ORANGE
        coneEdge.strokeWidth = radius * 0.045f
        cv.drawArc(oval, start, 60f, true, coneEdge)
        // 가운데 화살표
        val rad = Math.toRadians((dir - 90f).toDouble())
        val ex = tipX + (radius * 1.08f * Math.cos(rad)).toFloat()
        val ey = tipY + (radius * 1.08f * Math.sin(rad)).toFloat()
        coneEdge.strokeWidth = radius * 0.06f
        cv.drawLine(tipX, tipY, ex, ey, coneEdge)
        val head = radius * 0.2f
        for (side in arrayOf(-150.0, 150.0)) {
            val a = rad + Math.toRadians(side)
            cv.drawLine(ex, ey, ex + (head * Math.cos(a)).toFloat(), ey + (head * Math.sin(a)).toFloat(), coneEdge)
        }
    }

    // 원형 카메라 핀 색 (보내 주신 아이콘 기준)
    private const val RING = 0xFFEDEDED.toInt()        // 바깥 흰 테두리
    private const val RING_EDGE = 0xFFC8C8C8.toInt()
    private const val RED_TOP = 0xFFEC6A74.toInt()     // 빨간 원 위쪽 밝은 부분
    private const val RED_BOTTOM = 0xFFD9434F.toInt()
    private const val RED_IDLE_TOP = 0xFFF2A3A9.toInt() // 사진이 아직 없는 핀은 연하게
    private const val RED_IDLE_BOTTOM = 0xFFE88089.toInt()
    private const val CAMERA = 0xFF6E1E28.toInt()      // 카메라 그림 (진한 자주)
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /**
     * 원형 카메라 핀. (tipX, tipY) = 원의 가운데 = 실제 위치. r = 바깥 원 반지름.
     * 배경 없이 원만 그린다. 아래에 핀 번호 꼬리표, 오른쪽 위에 촬영 횟수 배지.
     */
    fun draw(cv: Canvas, tipX: Float, tipY: Float, r: Float, label: String, filled: Boolean, count: Int, highlight: Boolean = false) {
        val cx = tipX
        val cy = tipY

        // 바깥 흰 테두리
        fill.color = RING
        cv.drawCircle(cx, cy, r, fill)
        stroke.color = if (highlight) 0xFF2F80ED.toInt() else RING_EDGE
        stroke.strokeWidth = if (highlight) r * 0.16f else r * 0.04f
        cv.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)

        // 빨간 원 (위가 조금 밝은 그라데이션)
        val ir = r * 0.84f
        grad.shader = android.graphics.LinearGradient(
            cx, cy - ir, cx, cy + ir,
            if (filled) RED_TOP else RED_IDLE_TOP, if (filled) RED_BOTTOM else RED_IDLE_BOTTOM,
            android.graphics.Shader.TileMode.CLAMP
        )
        cv.drawCircle(cx, cy, ir, grad)
        grad.shader = null

        // 카메라 몸통
        val bw = r * 1.14f
        val bh = r * 0.72f
        val by = cy + r * 0.07f
        fill.color = CAMERA
        rect.set(cx - bw / 2f, by - bh / 2f, cx + bw / 2f, by + bh / 2f)
        cv.drawRoundRect(rect, r * 0.1f, r * 0.1f, fill)
        // 위쪽 뷰파인더 돌출부
        val tw = r * 0.46f
        val th = r * 0.17f
        rect.set(cx - tw / 2f, by - bh / 2f - th, cx + tw / 2f, by - bh / 2f + r * 0.04f)
        cv.drawRoundRect(rect, r * 0.06f, r * 0.06f, fill)
        // 렌즈 (빨간 링)
        stroke.color = if (filled) RED_BOTTOM else RED_IDLE_BOTTOM
        stroke.strokeWidth = r * 0.1f
        cv.drawCircle(cx, by, r * 0.23f, stroke)

        // 핀 번호 꼬리표 (아래)
        val shown = if (label.length > 6) label.take(6) else label
        text.textSize = r * 0.58f
        val tw2 = text.measureText(shown) + r * 0.5f
        val ty = cy + r * 1.05f
        rect.set(cx - tw2 / 2f, ty, cx + tw2 / 2f, ty + r * 0.78f)
        fill.color = INK
        cv.drawRoundRect(rect, r * 0.39f, r * 0.39f, fill)
        text.color = Color.WHITE
        cv.drawText(shown, cx, rect.centerY() - (text.ascent() + text.descent()) / 2f, text)

        // 촬영 횟수 배지 (오른쪽 위)
        if (count > 0) {
            val br = r * 0.4f
            val bx = cx + r * 0.78f
            val byy = cy - r * 0.78f
            fill.color = Color.WHITE
            cv.drawCircle(bx, byy, br + r * 0.07f, fill)
            fill.color = INK
            cv.drawCircle(bx, byy, br, fill)
            text.color = Color.WHITE
            text.textSize = br * 1.1f
            val t = if (count > 99) "99+" else count.toString()
            cv.drawText(t, bx, byy - (text.ascent() + text.descent()) / 2f, text)
        }
    }
}
