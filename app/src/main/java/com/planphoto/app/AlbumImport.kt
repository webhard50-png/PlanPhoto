package com.planphoto.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 앨범(갤러리)에서 고른 사진을 핀에 넣는다.
 * 사진마다 촬영처럼 원본 + 좌표 사진 두 장을 만들고 PDFPIC 폴더에도 저장한다.
 * 촬영 시각은 사진의 EXIF 촬영 시각을 쓰고, 없으면 가져온 시각을 쓴다.
 */
object AlbumImport {

    private val main = Handler(Looper.getMainLooper())
    private val exifFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    /** @param done (가져온 장수, 실패 장수) — 메인 스레드에서 호출 */
    fun importPhotos(app: Context, plan: Plan, pin: Pin, uris: List<Uri>, done: (Int, Int) -> Unit) {
        if (uris.isEmpty()) { done(0, 0); return }
        var remaining = uris.size
        var ok = 0
        var fail = 0
        fun finishOne(success: Boolean) {
            if (success) ok++ else fail++
            remaining--
            if (remaining == 0) done(ok, fail)
        }
        PhotoSaver.worker.execute {
            for (uri in uris) {
                val path = try {
                    val time = takenTime(app, uri) ?: System.currentTimeMillis()
                    val coord = PhotoProcessor.coordOf(app, plan, pin)
                    val p = Repo.newOriginalPath(app, plan, pin, time, coord)
                    val f = Repo.photoFile(app, p)
                    app.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("사진을 열 수 없습니다")
                    if (f.length() == 0L) { f.delete(); throw IllegalStateException("빈 파일") }
                    Pair(p, time)
                } catch (e: Throwable) {
                    null
                }
                if (path == null) { main.post { finishOne(false) }; continue }
                // 좌표 사진 만들기는 같은 작업 줄에서 순서대로 이어서 처리된다
                PhotoSaver.process(app, plan, pin, path.first, path.second) { _, _ -> finishOne(true) }
            }
        }
    }

    /** 사진에 기록된 촬영 시각 (EXIF) */
    private fun takenTime(c: Context, uri: Uri): Long? = try {
        c.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val s = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            s?.let { synchronized(exifFormat) { exifFormat.parse(it)?.time } }
        }
    } catch (_: Exception) {
        null
    }
}
