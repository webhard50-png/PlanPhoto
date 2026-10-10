package com.planphoto.app

import android.app.Activity
import android.os.LocaleList
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/** 한글 이름 입력과 정리 */
object Names {

    /**
     * 이름 정리:
     *  - 맥/아이폰에서 온 파일의 분리된 자모(ㅍㅕㅇ…)를 완성형 한글로 합침 (NFC)
     *  - 일부 앱이 넘겨주는 %ED%8F%89 같은 인코딩된 이름을 한글로 되돌림
     *  - 앞뒤 공백, 줄바꿈 제거
     */
    fun clean(raw: String): String {
        var s = raw
        if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)) {
            s = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
        return s.replace(Regex("[\\r\\n\\t]"), " ").trim()
    }

    /**
     * 한글 키보드로 열리는 이름 입력 대화상자.
     * 마지막 글자를 조합 중이어도 그대로 저장된다.
     */
    fun askName(a: Activity, title: String, current: String, hint: String = "", onSave: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(current)
            setSelection(text.length)
            setHint(hint)
            isSingleLine = true
            // 일반 텍스트 입력 (비밀번호/URL 형식이면 한글 키보드가 막힘)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            // 키보드에 한국어를 우선 요청
            imeHintLocales = LocaleList(Locale.KOREAN, Locale.getDefault())
        }
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        val box = FrameLayout(a).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        val dlg = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()

        fun save() {
            val v = clean(input.text.toString())
            if (v.isEmpty()) { input.error = "이름을 입력하세요"; return }
            onSave(v)
            dlg.dismiss()
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
            input.requestFocus()
        }
        // 대화상자가 열리면 키보드도 바로 뜨게
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
    }
}
