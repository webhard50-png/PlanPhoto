package com.planphoto.app

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 한 번 촬영에 생기는 사진 두 장. 경로는 앱 사진 폴더 기준 상대 경로. */
data class Photo(
    val original: String,
    val marked: String,
    val takenAt: Long,
    val xMm: Float,
    val yMm: Float,
    /** 촬영 당시 좌표 표기 (예: "X3+2 · Y1+4") */
    val label: String = "",
)

data class Pin(
    val id: String,
    var label: String,
    var page: Int,
    /** 도면 페이지 기준 0~1 (좌상단 원점, 화면 좌표) */
    var x: Float,
    var y: Float,
    val photos: MutableList<Photo> = mutableListOf(),
    /** 촬영 방향 (도면 위쪽 = 0°, 시계 방향). null = 방향 없음 */
    var dir: Float? = null,
)

data class Plan(
    val id: String,
    var name: String,
    val createdAt: Long,
    val pins: MutableList<Pin> = mutableListOf(),
    /** 처음 불러온 PDF의 쪽 크기(pt). PDF를 교체해도 이 크기에 맞춰 그린다 */
    val baseSizes: MutableList<Pair<Float, Float>> = mutableListOf(),
    /** A1 좌표 격자: 가로 칸 수(0 = 기본 26), 세로 칸 수(0 = 정사각형 칸이 되도록 자동) */
    var gridCols: Int = 0,
    var gridRows: Int = 0,
    /** 도면 위에 격자 표시 */
    var showGrid: Boolean = true,
    /** PDF 교체 횟수와 마지막 교체 시각 */
    var revision: Int = 0,
    var replacedAt: Long = 0L,
) {
    val photoCount get() = pins.sumOf { it.photos.size }
}

object Repo {
    private var cache: MutableList<Plan>? = null

    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)

    private fun dataFile(c: Context) = File(c.filesDir, "data.json")

    @Synchronized
    fun plans(c: Context): MutableList<Plan> {
        cache?.let { return it }
        val list = mutableListOf<Plan>()
        val f = dataFile(c)
        if (f.exists()) {
            try {
                val arr = JSONObject(f.readText()).optJSONArray("plans") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val plan = Plan(o.getString("id"), Names.clean(o.getString("name")), o.optLong("createdAt"))
                    o.optJSONArray("base")?.let { ba ->
                        for (b in 0 until ba.length()) {
                            val wh = ba.getJSONArray(b)
                            plan.baseSizes += Pair(wh.getDouble(0).toFloat(), wh.getDouble(1).toFloat())
                        }
                    }
                    plan.revision = o.optInt("revision")
                    plan.gridCols = o.optInt("gridCols")
                    plan.gridRows = o.optInt("gridRows")
                    plan.showGrid = o.optBoolean("showGrid", true)
                    plan.replacedAt = o.optLong("replacedAt")
                    val pa = o.optJSONArray("pins") ?: JSONArray()
                    for (j in 0 until pa.length()) {
                        val p = pa.getJSONObject(j)
                        val ph = p.optJSONArray("photos") ?: JSONArray()
                        val photos = MutableList(ph.length()) { k ->
                            val q = ph.getJSONObject(k)
                            Photo(
                                q.getString("original"), q.getString("marked"), q.getLong("takenAt"),
                                q.optDouble("xMm").toFloat(), q.optDouble("yMm").toFloat(),
                                q.optString("label")
                            )
                        }
                        plan.pins.add(
                            Pin(
                                p.getString("id"), p.getString("label"), p.optInt("page"),
                                p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), photos,
                                if (p.has("dir")) p.getDouble("dir").toFloat() else null
                            )
                        )
                    }
                    list.add(plan)
                }
            } catch (_: Exception) {
            }
        }
        cache = list
        return list
    }

    @Synchronized
    fun save(c: Context) {
        val arr = JSONArray()
        for (plan in plans(c)) {
            val pins = JSONArray()
            for (pin in plan.pins) {
                val photos = JSONArray()
                pin.photos.forEach {
                    photos.put(
                        JSONObject().put("original", it.original).put("marked", it.marked)
                            .put("takenAt", it.takenAt).put("xMm", it.xMm.toDouble()).put("yMm", it.yMm.toDouble())
                            .put("label", it.label)
                    )
                }
                pins.put(
                    JSONObject().put("id", pin.id).put("label", pin.label).put("page", pin.page)
                        .put("x", pin.x.toDouble()).put("y", pin.y.toDouble()).put("photos", photos)
                        .apply { pin.dir?.let { put("dir", it.toDouble()) } }
                )
            }
            arr.put(
                JSONObject().put("id", plan.id).put("name", plan.name).put("createdAt", plan.createdAt)
                    .put("pins", pins)
                    .put("base", JSONArray(plan.baseSizes.map { JSONArray().put(it.first.toDouble()).put(it.second.toDouble()) }))
                    .put("revision", plan.revision)
                    .put("gridCols", plan.gridCols).put("gridRows", plan.gridRows).put("showGrid", plan.showGrid).put("replacedAt", plan.replacedAt)
            )
        }
        val tmp = File(c.filesDir, "data.json.tmp")
        tmp.writeText(JSONObject().put("plans", arr).toString())
        tmp.renameTo(dataFile(c))
    }

    fun plan(c: Context, id: String?) = plans(c).find { it.id == id }

    fun pdfFile(c: Context, planId: String) = File(c.filesDir, "plans/$planId.pdf")

    private fun photoRoot(c: Context) =
        c.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(c.filesDir, "Pictures")

    fun planPhotoDir(c: Context, planId: String) = File(photoRoot(c), planId)

    fun photoFile(c: Context, relPath: String) = File(photoRoot(c), relPath)

    /** 파일 이름에 쓸 수 없는 문자를 정리 */
    fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N}_\\-. ]"), "_").trim().ifEmpty { "이름없음" }

    /**
     * 핀·날짜별 폴더 안에 원본 파일 경로를 새로 만든다.
     * 파일 이름 = 좌표, 날짜, 시간 순: B10_20261014_145012_원본.jpg
     * (행 번호 자릿수를 맞춰 B02, B10 … 이름순 정렬 = 좌표→날짜→시간 순)
     */
    fun newOriginalPath(c: Context, plan: Plan, pin: Pin, time: Long, coord: Coord): String {
        val day = dayFormat.format(Date(time))
        val dir = File(photoRoot(c), "${plan.id}/${pin.id}/$day").apply { mkdirs() }
        val base = coord.fileToken() + "_" + stampFormat.format(Date(time))
        var name = "${base}_원본.jpg"
        var n = 2
        while (File(dir, name).exists()) name = "${base}-${n++}_원본.jpg"
        File(dir, name).createNewFile()   // 같은 초에 연속 촬영해도 이름이 겹치지 않게 미리 잡아 둠
        return "${plan.id}/${pin.id}/$day/$name"
    }

    fun markedPathFor(originalPath: String) = originalPath.removeSuffix("_원본.jpg") + "_좌표.jpg"

    /** 휴대폰 저장 폴더(내장 메모리/PDFPIC)에 복사 — 모든 사진을 한 폴더에, 이름 = 좌표_날짜_시간 */
    @Suppress("UNUSED_PARAMETER")
    fun exportToGallery(c: Context, file: File, plan: Plan, pin: Pin, time: Long) {
        PdfPicStorage.savePhoto(c, file, time)
    }

    fun importPdf(c: Context, uri: Uri): Plan {
        var name = "평면도"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
        }
        name = Names.clean(name).removeSuffix(".pdf").removeSuffix(".PDF").ifEmpty { "평면도" }
        val id = UUID.randomUUID().toString().take(8)
        val dest = pdfFile(c, id).apply { parentFile?.mkdirs() }
        c.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("파일을 읽을 수 없습니다")
        val sizes = try {
            PdfPages.pageSizes(dest)
        } catch (e: Exception) {
            dest.delete()
            throw IllegalStateException("PDF를 열 수 없습니다 (암호가 걸린 파일인지 확인하세요)")
        }
        val plan = Plan(id, name, System.currentTimeMillis())
        plan.baseSizes += sizes
        plans(c).add(0, plan)
        save(c)
        return plan
    }

    /**
     * 평면도 PDF 교체. 핀·사진은 그대로 두고, 새 PDF는 기존 쪽 크기(기준 크기)에 맞춰 그린다.
     * 이전 PDF는 plans/{id}_rev{n}.pdf 로 보관한다.
     * @return 안내 문구
     */
    fun replacePdf(c: Context, plan: Plan, uri: Uri): String {
        val cur = pdfFile(c, plan.id)
        // 예전 버전에서 만든 도면은 기준 크기가 없으므로 지금 PDF에서 기록
        if (plan.baseSizes.isEmpty() && cur.exists()) plan.baseSizes += PdfPages.pageSizes(cur)

        val tmp = File(cur.parentFile, "${plan.id}_new.pdf")
        c.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("파일을 읽을 수 없습니다")
        val newSizes = try {
            PdfPages.pageSizes(tmp)
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("PDF를 열 수 없습니다 (암호가 걸린 파일인지 확인하세요)")
        }
        // 기존보다 쪽이 많으면 늘어난 쪽은 그 쪽 크기를 기준으로
        for (i in plan.baseSizes.size until newSizes.size) plan.baseSizes += newSizes[i]

        plan.revision += 1
        if (cur.exists()) cur.renameTo(File(cur.parentFile, "${plan.id}_rev${plan.revision}.pdf"))
        tmp.renameTo(cur)
        plan.replacedAt = System.currentTimeMillis()
        save(c)

        val oldCount = plan.baseSizes.size
        val notes = mutableListOf("새 도면을 기존 크기에 맞췄습니다. 핀 ${plan.pins.size}개와 사진은 그대로입니다.")
        val (bw, bh) = plan.baseSizes[0]
        val (nw, nh) = newSizes[0]
        if (kotlin.math.abs(bw / bh - nw / nh) > 0.02f) notes += "용지 비율이 달라 남는 부분은 흰 여백으로 표시됩니다."
        if (newSizes.size < oldCount) {
            val lost = plan.pins.count { it.page >= newSizes.size }
            notes += "새 PDF는 ${newSizes.size}쪽입니다." + if (lost > 0) " ${newSizes.size + 1}쪽 이후에 있던 핀 ${lost}개는 해당 쪽이 없어 표시되지 않습니다 (사진은 보관됨)." else ""
        }
        return notes.joinToString("\n")
    }

    fun deletePlan(c: Context, plan: Plan) {
        pdfFile(c, plan.id).delete()
        pdfFile(c, plan.id).parentFile?.listFiles { f -> f.name.startsWith("${plan.id}_") }?.forEach { it.delete() }
        planPhotoDir(c, plan.id).deleteRecursively()
        plans(c).remove(plan)
        save(c)
    }

    fun deletePhoto(c: Context, pin: Pin, photo: Photo) {
        photoFile(c, photo.original).delete()
        photoFile(c, photo.marked).delete()
        pin.photos.remove(photo)
        save(c)
    }

    fun deletePin(c: Context, plan: Plan, pin: Pin) {
        File(planPhotoDir(c, plan.id), pin.id).deleteRecursively()
        plan.pins.remove(pin)
        save(c)
    }

    fun nextLabel(plan: Plan): String {
        val n = plan.pins.mapNotNull { it.label.toIntOrNull() }.maxOrNull() ?: 0
        return (n + 1).toString()
    }

    fun uriFor(c: Context, f: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)

    fun viewImage(a: Activity, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(a, f), "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(a, "사진을 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /** 좌표 사진 / 원본 / 모두 중에서 골라 공유 */
    fun sharePhotos(a: Activity, photos: List<Photo>) {
        if (photos.isEmpty()) {
            Toast.makeText(a, "공유할 사진이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(a)
            .setTitle("사진 ${photos.size}건 공유")
            .setItems(arrayOf("좌표 표시 사진만", "원본 사진만", "두 종류 모두")) { _, which ->
                val paths = when (which) {
                    0 -> photos.map { it.marked }
                    1 -> photos.map { it.original }
                    else -> photos.flatMap { listOf(it.marked, it.original) }
                }
                val uris = ArrayList(paths.map { photoFile(a, it) }.filter { it.exists() }.map { uriFor(a, it) })
                if (uris.isEmpty()) return@setItems
                val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri("photos", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                a.startActivity(Intent.createChooser(i, "사진 공유"))
            }
            .show()
    }
}
