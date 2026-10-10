package com.planphoto.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 휴대폰 저장 폴더: 내장 메모리(SDCARD) 맨 위의 PDFPIC 폴더.
 *   /sdcard/PDFPIC/X0004520_Y0012300_20261010_140732_좌표.jpg
 *   /sdcard/PDFPIC/X0004520_Y0012300_20261010_140732_원본.jpg
 *   /sdcard/PDFPIC/도면이름_20261010_1452.zip   (내보내기)
 * 하위 폴더 없이 모든 사진을 한 폴더에 저장하므로, 이름순 정렬 = 좌표 → 날짜 → 시간 순.
 *
 * Android 10 이상에서 내장 메모리 맨 위에 쓰려면 "모든 파일 접근" 권한이 필요하다.
 * 권한이 없으면 사진은 Pictures/PDFPIC, ZIP은 Download/PDFPIC 에 대신 저장한다.
 */
object PdfPicStorage {

    const val FOLDER = "PDFPIC"

    val dir: File get() = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** 사용자에게 보여 줄 저장 위치 */
    fun whereText(c: Context, isZip: Boolean = false) =
        if (hasAccess(c)) "내장 메모리/$FOLDER"
        else if (isZip) "다운로드/$FOLDER (모든 파일 접근 권한이 없어 대신 저장)"
        else "사진/$FOLDER (모든 파일 접근 권한이 없어 대신 저장)"

    /** 같은 이름이 있으면 -2, -3 … 을 붙인다 */
    private fun freeFile(name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (f.exists()) f = File(dir, "$base-${n++}" + if (ext.isNotEmpty()) ".$ext" else "")
        return f
    }

    /** 사진 한 장을 PDFPIC 에 복사 (촬영 시각을 파일 시각으로) */
    fun savePhoto(c: Context, file: File, time: Long) {
        if (!file.exists() || file.length() == 0L) return
        try {
            if (hasAccess(c)) {
                dir.mkdirs()
                val out = freeFile(file.name)
                file.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                out.setLastModified(time)
                MediaScannerConnection.scanFile(c, arrayOf(out.absolutePath), arrayOf("image/jpeg"), null)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_TAKEN, time)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                insertCopy(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, file)
            }
        } catch (_: Exception) {
        }
    }

    /** 내보내기 ZIP을 PDFPIC 에 저장. 저장된 위치 문구를 돌려준다 */
    fun saveZip(c: Context, zip: File): String {
        if (hasAccess(c)) {
            dir.mkdirs()
            val out = freeFile(zip.name)
            zip.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(c, arrayOf(out.absolutePath), arrayOf("application/zip"), null)
            return "내장 메모리/$FOLDER/${out.name}"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        insertCopy(c, MediaStore.Downloads.EXTERNAL_CONTENT_URI, values, zip)
        return "다운로드/$FOLDER/${zip.name}\n(모든 파일 접근 권한을 허용하면 내장 메모리/$FOLDER 에 저장됩니다)"
    }

    private fun insertCopy(c: Context, collection: Uri, values: ContentValues, file: File) {
        val resolver = c.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("저장 폴더에 쓸 수 없습니다")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // --- 권한 요청 ---

    private const val PREFS = "storage"
    private const val KEY_LATER = "later"

    /**
     * 권한이 없으면 안내 후 설정 화면을 연다. "나중에"를 고르면 다시 묻지 않는다(메뉴에서 다시 열 수 있음).
     * @param force true 면 "나중에"를 골랐어도 다시 묻는다
     * @param requestLegacy Android 10 에서 저장 권한을 요청하는 함수
     */
    fun ask(a: Activity, force: Boolean, requestLegacy: () -> Unit) {
        if (hasAccess(a)) {
            if (force) AlertDialog.Builder(a).setTitle("저장 폴더")
                .setMessage("사진과 내보내기 파일은 내장 메모리/$FOLDER 폴더에 저장됩니다.")
                .setPositiveButton("확인", null).show()
            return
        }
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_LATER, false)) return
        AlertDialog.Builder(a)
            .setTitle("저장 폴더: 내장 메모리/$FOLDER")
            .setMessage(
                "사진(좌표 사진 + 원본)과 내보내기 파일을 휴대폰 내장 메모리의 $FOLDER 폴더에 저장하려면 " +
                    "'모든 파일 접근' 권한이 필요합니다.\n\n" +
                    "다음 화면에서 '평면도 사진'의 권한을 켜 주세요.\n" +
                    "허용하지 않으면 사진은 사진/$FOLDER 폴더에 저장됩니다."
            )
            .setPositiveButton("권한 설정") { _, _ -> openSettings(a, requestLegacy) }
            .setNegativeButton("나중에") { _, _ -> prefs.edit().putBoolean(KEY_LATER, true).apply() }
            .show()
    }

    private fun openSettings(a: Activity, requestLegacy: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {
                a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestLegacy()
        }
    }
}
