package com.planphoto.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 도면 하나를 ZIP으로 내보낸다.
 *  {도면}/{도면}.pdf            원본 PDF
 *  {도면}/{도면}_핀표시.pdf     핀 번호·촬영 방향을 그려 넣은 도면
 *  {도면}/사진목록.csv          핀, 좌표, 방향, 촬영 시각, 파일 이름 (엑셀에서 열림)
 *  {도면}/사진/핀3/2026-10-10/X…_좌표.jpg, X…_원본.jpg
 */
object Exporter {

    private val worker = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA)

    /** 저장/공유를 고르게 한 뒤 백그라운드에서 ZIP을 만든다 */
    fun start(a: Activity, plan: Plan) {
        val photos = plan.photoCount
        AlertDialog.Builder(a)
            .setTitle("'${plan.name}' 내보내기")
            .setMessage("PDF 도면, 핀 표시 도면, 사진 ${photos}회분(좌표 사진 + 원본), 사진 목록(CSV)을 ZIP 하나로 묶습니다.")
            .setPositiveButton("PDFPIC 폴더에 저장") { _, _ -> run(a, plan, share = false) }
            .setNeutralButton("공유하기") { _, _ -> run(a, plan, share = true) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun run(a: Activity, plan: Plan, share: Boolean) {
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val status = TextView(a).apply { text = "ZIP 만드는 중…"; setPadding(pad / 2, 0, 0, 0) }
        val box = LinearLayout(a).apply {
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(a))
            addView(status)
        }
        val dlg = AlertDialog.Builder(a).setView(box).setCancelable(false).show()
        val app = a.applicationContext
        worker.execute {
            val result = try {
                Result.success(buildZip(app, plan))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            a.runOnUiThread {
                dlg.dismiss()
                val zip = result.getOrNull()
                if (zip == null) {
                    Toast.makeText(a, "내보내기에 실패했습니다: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (share) shareZip(a, zip) else saveToPdfPic(a, zip)
            }
        }
    }

    fun buildZip(c: Context, plan: Plan): File {
        val dir = File(c.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val base = Repo.safe(plan.name)
        val zipFile = File(dir, "${base}_${stamp.format(Date())}.zip")
        val pdf = Repo.pdfFile(c, plan.id)
        val marked = File(dir, "${base}_핀표시.pdf")
        writeMarkedPdf(c, plan, marked)

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            fun put(name: String, f: File) {
                if (!f.exists()) return
                zip.putNextEntry(ZipEntry("$base/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            put("$base.pdf", pdf)
            put("${base}_핀표시.pdf", marked)

            val csv = StringBuilder("﻿핀,쪽,좌표,X(mm),Y(mm),방향,촬영일시,좌표 사진,원본 사진\n")
            for (pin in plan.pins) {
                val coord = PhotoProcessor.coordOf(c, plan, pin)
                val dir0 = pin.dir?.let { "${Math.round(it)}°" } ?: ""
                if (pin.photos.isEmpty()) {
                    csv.append(row(pin.label, pin.page + 1, coord.cell, coord.paperX, coord.paperY, dir0, "", "", ""))
                }
                for (ph in pin.photos.sortedBy { it.takenAt }) {
                    val day = Repo.dayFormat.format(Date(ph.takenAt))
                    val folder = "사진/핀${Repo.safe(pin.label)}/$day"
                    val markedF = Repo.photoFile(c, ph.marked)
                    val origF = Repo.photoFile(c, ph.original)
                    put("$folder/${markedF.name}", markedF)
                    if (ph.original != ph.marked) put("$folder/${origF.name}", origF)
                    csv.append(
                        row(
                            pin.label, pin.page + 1, ph.label.substringBefore(" ·").ifEmpty { coord.cell }, ph.xMm, ph.yMm, dir0,
                            Repo.timeFormat.format(Date(ph.takenAt)),
                            "$folder/${markedF.name}",
                            if (ph.original != ph.marked) "$folder/${origF.name}" else ""
                        )
                    )
                }
            }
            zip.putNextEntry(ZipEntry("$base/사진목록.csv"))
            zip.write(csv.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        marked.delete()
        return zipFile
    }

    private fun row(label: String, page: Int, cell: String, x: Float, y: Float, dir: String, time: String, m: String, o: String): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return listOf(q(label), page.toString(), q(cell), String.format(Locale.US, "%.1f", x), String.format(Locale.US, "%.1f", y),
            q(dir), q(time), q(m), q(o)).joinToString(",") + "\n"
    }

    /** 원본 도면 각 쪽 위에 핀과 촬영 방향을 그려 새 PDF로 저장 */
    private fun writeMarkedPdf(c: Context, plan: Plan, out: File) {
        val doc = PdfDocument()
        try {
            ParcelFileDescriptor.open(Repo.pdfFile(c, plan.id), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = PdfRenderer(pfd)
                try {
                    for (i in 0 until r.pageCount) {
                        val page = r.openPage(i)
                        val fit = PdfPages.fit(plan, i, page)
                        val wPt = fit.baseW.toInt()
                        val hPt = fit.baseH.toInt()
                        val bmp = PdfPages.renderFull(page, fit, 3000, 3f, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val cv = android.graphics.Canvas(bmp)
                        val r0 = min(bmp.width, bmp.height) * 0.014f
                        // A1 격자와 가장자리 칸 이름
                        if (plan.showGrid) drawCells(cv, bmp.width.toFloat(), bmp.height.toFloat(), CellGrid.dims(plan, fit.baseW, fit.baseH))
                        val pins = plan.pins.filter { it.page == i }
                        for (p in pins) p.dir?.let {
                            PinMarker.drawCone(cv, p.x * bmp.width, p.y * bmp.height, r0 * 3.6f, it)
                        }
                        for (p in pins) {
                            PinMarker.draw(cv, p.x * bmp.width, p.y * bmp.height, r0, p.label,
                                filled = p.photos.isNotEmpty(), count = p.photos.size)
                        }

                        val pg = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
                        pg.canvas.drawBitmap(bmp, null, Rect(0, 0, wPt, hPt), Paint(Paint.FILTER_BITMAP_FLAG))
                        doc.finishPage(pg)
                        bmp.recycle()
                    }
                } finally {
                    r.close()
                }
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun drawCells(cv: android.graphics.Canvas, w: Float, h: Float, d: CellGrid.Dims) {
        val cw = w / d.cols
        val ch = h / d.rows
        val line = Paint().apply { color = Color.argb(70, 0x2F, 0x80, 0xED); strokeWidth = max(1f, min(w, h) / 1500f) }
        for (i in 1 until d.cols) cv.drawLine(i * cw, 0f, i * cw, h, line)
        for (j in 1 until d.rows) cv.drawLine(0f, j * ch, w, j * ch, line)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0x2F, 0x80, 0xED); textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = min(min(cw, ch) * 0.45f, min(w, h) * 0.018f)
        }
        val base = -(t.ascent() + t.descent()) / 2f
        val m = t.textSize * 0.9f
        for (i in 0 until d.cols) {
            val x = (i + 0.5f) * cw
            cv.drawText(CellGrid.colName(i), x, m + base, t)
            cv.drawText(CellGrid.colName(i), x, h - m + base, t)
        }
        for (j in 0 until d.rows) {
            val y = (j + 0.5f) * ch
            cv.drawText((j + 1).toString(), m * 1.2f, y + base, t)
            cv.drawText((j + 1).toString(), w - m * 1.2f, y + base, t)
        }
    }

    private fun shareZip(a: Activity, zip: File) {
        val uri = Repo.uriFor(a, zip)
        val i = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "도면과 사진 보내기"))
    }

    private fun saveToPdfPic(a: Activity, zip: File) {
        try {
            val where = PdfPicStorage.saveZip(a, zip)
            AlertDialog.Builder(a)
                .setTitle("저장했습니다")
                .setMessage(where + "\n\n'내 파일' 앱에서 찾을 수 있습니다.")
                .setPositiveButton("확인", null)
                .setNeutralButton("바로 공유") { _, _ -> shareZip(a, zip) }
                .show()
        } catch (e: Exception) {
            Toast.makeText(a, "저장에 실패했습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
