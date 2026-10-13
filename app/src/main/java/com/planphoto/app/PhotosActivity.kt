package com.planphoto.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 한 핀의 사진 기록. 날짜별로 묶고, 각 촬영은 좌표 사진 + 원본 두 장. */
class PhotosActivity : CaptureActivity() {

    companion object {
        const val EXTRA_PIN = "pin_id"
    }

    private lateinit var plan: Plan
    private lateinit var pin: Pin
    private lateinit var container: LinearLayout
    private lateinit var empty: TextView
    private lateinit var coordText: TextView
    private val hm = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val dayTitle = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plan = Repo.plan(this, intent.getStringExtra(PlanActivity.EXTRA_PLAN)) ?: run { finish(); return }
        pin = plan.pins.find { it.id == intent.getStringExtra(EXTRA_PIN) } ?: run { finish(); return }
        setContentView(R.layout.activity_photos)
        title = "핀 ${pin.label} 사진 기록"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        container = findViewById(R.id.photo_container)
        empty = findViewById(R.id.empty_text)
        coordText = findViewById(R.id.coord_text)
        findViewById<Button>(R.id.capture_button).setOnClickListener { capturePhoto(plan, pin) }
        findViewById<Button>(R.id.album_button).setOnClickListener { pickFromAlbum(plan, pin) }
        rebuild()
    }

    override fun onPhotoSaved(pin: Pin) = rebuild()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rebuild() {
        val gen = ++generation
        container.removeAllViews()
        coordText.text = "${plan.name} · ${pin.page + 1}쪽 · ${PhotoProcessor.coordOf(this, plan, pin).mainText()}"
        empty.visibility = if (pin.photos.isEmpty()) View.VISIBLE else View.GONE

        val sub = ContextCompat.getColor(this, R.color.sub_text)
        // 날짜별 묶음, 최신 날짜·최신 시간이 위로
        val byDay = pin.photos.sortedByDescending { it.takenAt }.groupBy { Repo.dayFormat.format(Date(it.takenAt)) }
        val thumbW = resources.displayMetrics.widthPixels - dp(32)

        for ((_, photos) in byDay) {
            container.addView(TextView(this).apply {
                text = "${dayTitle.format(Date(photos.first().takenAt))}  ·  ${photos.size}회"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, dp(8))
            })
            for (photo in photos) {
                val img = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    minimumHeight = dp(160)
                    setBackgroundColor(ContextCompat.getColor(this@PhotosActivity, R.color.plan_bg))
                    setOnClickListener { Repo.viewImage(this@PhotosActivity, Repo.photoFile(this@PhotosActivity, photo.marked)) }
                    setOnLongClickListener { confirmDelete(photo); true }
                }
                container.addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(14))
                }
                row.addView(TextView(this).apply {
                    val where = if (photo.label.isNotEmpty()) "  ·  ${photo.label}" else ""
                    text = hm.format(Date(photo.takenAt)) + where + "\n" +
                        Repo.photoFile(this@PhotosActivity, photo.marked).name.removeSuffix("_좌표.jpg").removeSuffix("_원본.jpg")
                    textSize = 13f
                    setTextColor(sub)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "좌표 사진"
                    setOnClickListener { Repo.viewImage(this@PhotosActivity, Repo.photoFile(this@PhotosActivity, photo.marked)) }
                })
                if (photo.original != photo.marked) row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "원본"
                    setOnClickListener { Repo.viewImage(this@PhotosActivity, Repo.photoFile(this@PhotosActivity, photo.original)) }
                })
                container.addView(row)

                // 미리보기는 백그라운드에서
                val file = Repo.photoFile(this, photo.marked)
                worker.execute {
                    val bmp = PhotoProcessor.decodeRotated(file, thumbW.coerceAtMost(1400))
                    runOnUiThread { if (gen == generation && !isDestroyed && bmp != null) img.setImageBitmap(bmp) }
                }
            }
        }
    }

    private fun confirmDelete(photo: Photo) {
        AlertDialog.Builder(this)
            .setTitle("이 촬영 기록 삭제")
            .setMessage("${Repo.timeFormat.format(Date(photo.takenAt))}\n좌표 사진과 원본을 앱에서 삭제합니다. PDFPIC 폴더의 사진은 남습니다.")
            .setPositiveButton("삭제") { _, _ -> Repo.deletePhoto(this, pin, photo); rebuild() }
            .setNegativeButton("취소", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "이 핀 사진 공유")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> { Repo.sharePhotos(this, pin.photos.sortedBy { it.takenAt }); return true }
        }
        return super.onOptionsItemSelected(item)
    }
}
