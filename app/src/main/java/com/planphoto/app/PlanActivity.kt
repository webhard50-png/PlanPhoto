package com.planphoto.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class PlanActivity : CaptureActivity(), PlanView.Listener {

    companion object {
        const val EXTRA_PLAN = "plan_id"
        private const val MAX_SIDE_PX = 3500
    }

    private lateinit var plan: Plan
    private lateinit var planView: PlanView
    private lateinit var hint: TextView
    private lateinit var loading: ProgressBar
    private lateinit var modeButton: Button
    private lateinit var pageLabel: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    private var pageIndex = 0
    private var pageCount = 1
    private var addMode = false
    /** 핀 삭제 모드: 핀을 누르면 삭제 확인 */
    private var deleteMode = false
    private lateinit var deleteButton: Button
    private var movingPin: Pin? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plan = Repo.plan(this, intent.getStringExtra(EXTRA_PLAN)) ?: run { finish(); return }
        setContentView(R.layout.activity_plan)
        title = plan.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        planView = findViewById(R.id.plan_view)
        hint = findViewById(R.id.hint)
        loading = findViewById(R.id.loading)
        modeButton = findViewById(R.id.mode_button)
        deleteButton = findViewById(R.id.delete_button)
        pageLabel = findViewById(R.id.page_label)
        prev = findViewById(R.id.prev_page)
        next = findViewById(R.id.next_page)
        planView.listener = this

        pageIndex = savedInstanceState?.getInt("page") ?: 0
        addMode = savedInstanceState?.getBoolean("add") ?: false

        modeButton.setOnClickListener {
            when {
                planView.directionPin != null -> endDirection()
                movingPin != null -> { movingPin = null; planView.highlightPinId = null }
                deleteMode -> deleteMode = false
                else -> addMode = !addMode
            }
            updateUi()
        }
        deleteButton.setOnClickListener {
            if (planView.directionPin != null) endDirection()
            movingPin = null; planView.highlightPinId = null; addMode = false
            deleteMode = !deleteMode
            updateUi()
        }
        prev.setOnClickListener { if (pageIndex > 0) renderPage(pageIndex - 1) }
        next.setOnClickListener { if (pageIndex < pageCount - 1) renderPage(pageIndex + 1) }

        renderPage(pageIndex)
    }

    private val pickReplace = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PdfReplace.run(this, plan, uri) { renderPage(pageIndex) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pageIndex)
        outState.putBoolean("add", addMode)
    }

    override fun onResume() {
        super.onResume()
        if (::planView.isInitialized) refreshPins()
    }

    private fun renderPage(index: Int) {
        loading.visibility = View.VISIBLE
        worker.execute {
            var bmp: Bitmap? = null
            var count = 1
            PdfPages.withPage(this, plan, index) { page, fit, n ->
                count = n
                bmp = try {
                    PdfPages.renderFull(page, fit, MAX_SIDE_PX, 6f, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (e: Throwable) { null }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading.visibility = View.GONE
                if (bmp == null) {
                    Toast.makeText(this, "도면을 표시할 수 없습니다", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                pageCount = count
                pageIndex = index.coerceIn(0, count - 1)
                planView.bitmap?.takeIf { it !== bmp }?.recycle()
                planView.bitmap = bmp
                refreshPins()
            }
        }
    }

    private fun refreshPins() {
        planView.pins = plan.pins.filter { it.page == pageIndex }
        planView.cellDims = if (plan.showGrid && planView.bitmap != null) pageDims() else null
        updateUi()
    }

    private fun updateUi() {
        pageLabel.text = "${pageIndex + 1} / $pageCount"
        prev.isEnabled = pageIndex > 0
        next.isEnabled = pageIndex < pageCount - 1
        val showPages = pageCount > 1
        prev.visibility = if (showPages) View.VISIBLE else View.GONE
        next.visibility = if (showPages) View.VISIBLE else View.GONE
        pageLabel.visibility = if (showPages) View.VISIBLE else View.GONE

        modeButton.text = when {
            planView.directionPin != null -> "방향 완료"
            movingPin != null -> "이동 취소"
            deleteMode -> "삭제 끝내기"
            addMode -> "핀 추가 끝내기"
            else -> "+ 핀 추가"
        }
        deleteButton.text = if (deleteMode) "삭제 중" else "핀 삭제"
        deleteButton.isSelected = deleteMode
        deleteButton.visibility = if (planView.directionPin == null && movingPin == null && !addMode) View.VISIBLE else View.GONE
        val onPage = plan.pins.count { it.page == pageIndex }
        hint.text = when {
            planView.directionPin != null -> "핀 주변을 한 손가락으로 끌어 촬영 방향을 정하세요" +
                (planView.directionPin?.dir?.let { "\n지금 " + PinMarker.dirText(it) } ?: "")
            movingPin != null -> "핀 ${movingPin!!.label}을(를) 옮길 위치를 누르세요"
            deleteMode -> "삭제할 핀을 누르세요"
            addMode -> "핀을 놓을 위치를 누르세요 (놓은 뒤 촬영 또는 앨범 선택)"
            onPage == 0 -> "'+ 핀 추가'를 누르고 도면 위에 핀을 놓으세요"
            else -> "핀을 누르면 카메라 · 길게 누르면 사진 기록과 메뉴"
        }
    }

    // --- PlanView.Listener ---

    override fun onPlanTap(nx: Float, ny: Float) {
        movingPin?.let {
            it.x = nx; it.y = ny; it.page = pageIndex
            movingPin = null
            planView.highlightPinId = null
            Repo.save(this)
            refreshPins()
            toastCoord(it, "핀 ${it.label} 이동")
            return
        }
        if (!addMode) return
        val pin = Pin(UUID.randomUUID().toString().take(8), Repo.nextLabel(plan), pageIndex, nx, ny)
        plan.pins.add(pin)
        Repo.save(this)
        refreshPins()
        // 핀을 놓자마자 사진 촬영 / 앨범에서 가져오기 선택
        choosePhotoSource(plan, pin, "핀 ${pin.label} 추가 · 사진 넣기")
    }

    private fun toastCoord(pin: Pin, prefix: String) {
        val c = PhotoProcessor.coordOf(this, plan, pin)
        Toast.makeText(this, "$prefix · ${c.mainText()}", Toast.LENGTH_SHORT).show()
    }

    override fun onDirectionSet(pin: Pin) {
        Repo.save(this)
        updateUi()
    }

    private fun startDirection(pin: Pin) {
        addMode = false; movingPin = null; planView.highlightPinId = null
        if (pin.dir == null) { pin.dir = 0f; Repo.save(this) }
        planView.directionPin = pin
        updateUi()
    }

    private fun endDirection() {
        val pin = planView.directionPin
        planView.directionPin = null
        Repo.save(this)
        pin?.dir?.let { Toast.makeText(this, "핀 ${pin.label} 방향 ${PinMarker.dirText(it)}", Toast.LENGTH_SHORT).show() }
        updateUi()
    }

    override fun onPinTap(pin: Pin) {
        if (movingPin != null || planView.directionPin != null) return
        if (deleteMode) { confirmDeletePin(pin); return }
        capturePhoto(plan, pin)
    }

    private fun confirmDeletePin(pin: Pin) {
        val msg = if (pin.photos.isEmpty()) "사진이 없는 핀입니다."
        else "이 핀의 사진 ${pin.photos.size}회분(좌표 사진 + 원본)도 앱에서 삭제됩니다.\nPDFPIC 폴더의 사진은 남습니다."
        AlertDialog.Builder(this)
            .setTitle("핀 ${pin.label} 삭제")
            .setMessage(msg)
            .setPositiveButton("삭제") { _, _ ->
                Repo.deletePin(this, plan, pin)
                refreshPins()
                Toast.makeText(this, "핀 ${pin.label}을(를) 삭제했습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onPinLongPress(pin: Pin) {
        if (planView.directionPin != null) return
        val c = PhotoProcessor.coordOf(this, plan, pin)
        val dirItem = if (pin.dir == null) "방향 표시" else "방향 조절 (${PinMarker.dirText(pin.dir!!)})"
        val menu = mutableListOf<Pair<String, () -> Unit>>(
            "사진 촬영" to { capturePhoto(plan, pin) },
            "앨범에서 가져오기" to { pickFromAlbum(plan, pin) },
            "사진 기록 보기 (${pin.photos.size}회)" to { openPhotos(pin) },
            dirItem to { startDirection(pin) },
            "이름 변경" to { renamePin(pin) },
            "위치 이동" to {
                movingPin = pin; addMode = false
                planView.highlightPinId = pin.id
                updateUi()
            },
            "삭제" to { confirmDeletePin(pin) },
        )
        if (pin.dir != null) menu += "방향 지우기" to { pin.dir = null; Repo.save(this); refreshPins() }
        AlertDialog.Builder(this)
            .setTitle("핀 ${pin.label}  ·  ${c.mainText()}")
            .setItems(menu.map { it.first }.toTypedArray()) { _, which -> menu[which].second() }
            .show()
    }

    private fun renamePin(pin: Pin) {
        Names.askName(this, "핀 이름 변경", pin.label, "예: 12, 거실창") { v ->
            pin.label = v
            Repo.save(this); refreshPins()
        }
    }

    private fun openPhotos(pin: Pin) {
        startActivity(
            Intent(this, PhotosActivity::class.java)
                .putExtra(EXTRA_PLAN, plan.id).putExtra(PhotosActivity.EXTRA_PIN, pin.id)
        )
    }

    override fun onPhotoSaved(pin: Pin) = refreshPins()

    // --- 메뉴 ---

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 6, 0, "좌표 격자 설정 (A1·B10)")
        menu.add(0, 7, 0, "격자 보기 켜기/끄기")
        menu.add(0, 5, 0, "평면도 PDF 교체")
        menu.add(0, 4, 0, "PDF + 사진 내보내기")
        menu.add(0, 2, 1, "사진 전체 공유")
        menu.add(0, 3, 2, "화면 맞춤")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            4 -> { Exporter.start(this, plan); return true }
            6 -> { gridDialog(); return true }
            7 -> { plan.showGrid = !plan.showGrid; Repo.save(this); refreshPins(); return true }
            5 -> { PdfReplace.confirm(this, plan) { pickReplace.launch(arrayOf("application/pdf")) }; return true }
            2 -> { Repo.sharePhotos(this, plan.pins.flatMap { it.photos }); return true }
            3 -> { planView.bitmap = planView.bitmap; return true }
        }
        return super.onOptionsItemSelected(item)
    }

    /** 현재 쪽의 격자 칸 수 (좌표 계산과 같은 기준 크기 사용) */
    private fun pageDims(): CellGrid.Dims {
        val base = plan.baseSizes.getOrNull(pageIndex)
        val b = planView.bitmap
        return CellGrid.dims(plan, base?.first ?: b?.width?.toFloat() ?: 1f, base?.second ?: b?.height?.toFloat() ?: 1f)
    }

    /** A1 좌표 격자 칸 수 설정 */
    private fun gridDialog() {
        val cur = pageDims()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }
        val colsIn = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cur.cols.toString())
        }
        val rowsIn = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setHint("비우면 자동 (정사각형 칸)")
            if (plan.gridRows > 0) setText(plan.gridRows.toString())
        }
        box.addView(label("가로 칸 수 (A, B, C … / 26 = A~Z)"))
        box.addView(colsIn)
        box.addView(label("세로 칸 수 (1, 2, 3 …)"))
        box.addView(rowsIn)
        box.addView(TextView(this).apply {
            text = "지금: ${CellGrid.colName(0)}1 ~ ${CellGrid.colName(cur.cols - 1)}${cur.rows} (왼쪽 위가 A1)\n바꾸면 이후 사진의 좌표와 파일 이름에 적용됩니다."
            setPadding(0, pad / 2, 0, 0)
            textSize = 13f
        })
        AlertDialog.Builder(this)
            .setTitle("좌표 격자 (A1 · B10)")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                plan.gridCols = colsIn.text.toString().toIntOrNull()?.coerceIn(1, 702) ?: 0
                plan.gridRows = rowsIn.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 0
                plan.showGrid = true
                Repo.save(this)
                refreshPins()
                val d = pageDims()
                Toast.makeText(this, "격자 A1 ~ ${CellGrid.colName(d.cols - 1)}${d.rows}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("기본값") { _, _ ->
                plan.gridCols = 0; plan.gridRows = 0; plan.showGrid = true
                Repo.save(this); refreshPins()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
