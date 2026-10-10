package com.planphoto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlin.math.max
import kotlin.math.min

/**
 * 도면 쪽의 "기준 크기"와 현재 PDF를 그 크기에 맞추는 변환.
 *
 * 처음 불러온 PDF의 쪽 크기(pt)를 기준 크기로 기억해 두고,
 * 나중에 PDF를 교체하면 새 쪽을 기준 크기 안에 같은 비율로 맞춰(가운데 정렬) 그린다.
 * 그래서 핀 위치(0~1)와 좌표(mm)가 교체 전후로 그대로 유지된다.
 */
class PageFit(
    /** 기준 크기 (pt) — 좌표 계산과 화면 비율은 항상 이 크기를 따른다 */
    val baseW: Float,
    val baseH: Float,
    private val pageW: Float,
    private val pageH: Float,
) {
    /** 현재 PDF 쪽 → 기준 크기 배율 */
    val s get() = min(baseW / pageW, baseH / pageH)
    val dx get() = (baseW - pageW * s) / 2f
    val dy get() = (baseH - pageH * s) / 2f

    /** PDF 쪽 좌표(pt) → 기준 좌표(pt)에 k배 확대 후 (ox, oy)만큼 이동한 픽셀 좌표 */
    fun matrix(k: Float, ox: Float = 0f, oy: Float = 0f) = Matrix().apply {
        setScale(s, s)
        postTranslate(dx, dy)
        postScale(k, k)
        postTranslate(ox, oy)
    }
}

object PdfPages {

    /** 열린 PDF 쪽에 대한 맞춤 정보. 기준 크기가 없으면 현재 쪽 크기를 기준으로 쓴다. */
    fun fit(plan: Plan, index: Int, page: PdfRenderer.Page): PageFit {
        val base = plan.baseSizes.getOrNull(index)
        return PageFit(
            base?.first ?: page.width.toFloat(), base?.second ?: page.height.toFloat(),
            page.width.toFloat(), page.height.toFloat()
        )
    }

    /** PDF를 열어 한 쪽에 대해 block 실행 (쪽 번호는 범위 안으로 맞춤) */
    fun <T> withPage(c: Context, plan: Plan, index: Int, block: (PdfRenderer.Page, PageFit, Int) -> T): T? {
        return try {
            ParcelFileDescriptor.open(Repo.pdfFile(c, plan.id), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = PdfRenderer(pfd)
                try {
                    val i = index.coerceIn(0, r.pageCount - 1)
                    val page = r.openPage(i)
                    try { block(page, fit(plan, i, page), r.pageCount) } finally { page.close() }
                } finally { r.close() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 기준 크기 비율로 쪽 전체를 비트맵으로 (긴 변 maxSidePx 이하) */
    fun renderFull(page: PdfRenderer.Page, fit: PageFit, maxSidePx: Int, maxK: Float, mode: Int): Bitmap {
        val k = min(maxSidePx / max(fit.baseW, fit.baseH), maxK)
        val bmp = Bitmap.createBitmap((fit.baseW * k).toInt(), (fit.baseH * k).toInt(), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, fit.matrix(k), mode)
        return bmp
    }

    /** 파일의 모든 쪽 크기(pt) */
    fun pageSizes(file: java.io.File): List<Pair<Float, Float>> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val r = PdfRenderer(pfd)
            try {
                List(r.pageCount) { i ->
                    val p = r.openPage(i)
                    try { Pair(p.width.toFloat(), p.height.toFloat()) } finally { p.close() }
                }
            } finally { r.close() }
        }
}
