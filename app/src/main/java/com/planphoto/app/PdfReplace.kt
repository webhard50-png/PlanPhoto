package com.planphoto.app

import android.app.Activity
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/** 평면도 PDF 교체: 확인 → 파일 선택 → 백그라운드 교체 → 결과 안내 */
object PdfReplace {

    private val worker = Executors.newSingleThreadExecutor()

    fun confirm(a: Activity, plan: Plan, pick: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("평면도 PDF 교체")
            .setMessage(
                "'${plan.name}'의 도면을 새 PDF로 바꿉니다.\n\n" +
                    "• 새 도면은 기존 도면 크기에 맞춰 배율이 조정됩니다.\n" +
                    "• 핀 ${plan.pins.size}개, 방향, 사진 ${plan.photoCount}회분은 그대로 유지됩니다.\n" +
                    "• 이전 PDF는 앱 안에 보관됩니다."
            )
            .setPositiveButton("새 PDF 고르기") { _, _ -> pick() }
            .setNegativeButton("취소", null)
            .show()
    }

    fun run(a: Activity, plan: Plan, uri: Uri, onDone: () -> Unit) {
        val pad = (24 * a.resources.displayMetrics.density).toInt()
        val box = LinearLayout(a).apply {
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(a))
            addView(TextView(a).apply { text = "도면 교체 중…"; setPadding(pad / 2, 0, 0, 0) })
        }
        val dlg = AlertDialog.Builder(a).setView(box).setCancelable(false).show()
        val app = a.applicationContext
        worker.execute {
            val result = try {
                Result.success(Repo.replacePdf(app, plan, uri))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            a.runOnUiThread {
                dlg.dismiss()
                if (a.isDestroyed) return@runOnUiThread
                val msg = result.getOrNull()
                AlertDialog.Builder(a)
                    .setTitle(if (msg != null) "도면을 교체했습니다 (${plan.revision}차)" else "교체하지 못했습니다")
                    .setMessage(msg ?: (result.exceptionOrNull()?.message ?: "알 수 없는 오류"))
                    .setPositiveButton("확인", null)
                    .show()
                if (msg != null) onDone()
            }
        }
    }
}
