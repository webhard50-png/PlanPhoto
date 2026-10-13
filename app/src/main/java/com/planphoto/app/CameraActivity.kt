package com.planphoto.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.result.PickVisualMediaRequest
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import java.util.Locale
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * 앱 안 카메라. 셔터를 누르면 확인 없이 바로 저장하고, 화면은 그대로라 연속으로 찍을 수 있다.
 * 볼륨 버튼으로도 찍을 수 있다. 사진마다 원본 + 좌표 사진 두 장이 저장된다.
 * 줌: 두 손가락 벌리기/모으기, 배율 버튼(0.5×~10×), 슬라이더. 화면을 누르면 그 위치에 초점.
 * 앨범: 갤러리에서 사진을 골라 이 핀에 바로 넣을 수 있다.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAN = "plan_id"
        const val EXTRA_PIN = "pin_id"
        /** 권한이 없어 기본 카메라 앱으로 찍기를 고른 경우 */
        const val RESULT_USE_SYSTEM = RESULT_FIRST_USER + 1
    }

    private lateinit var plan: Plan
    private lateinit var pin: Pin
    private lateinit var preview: PreviewView
    private lateinit var flash: View
    private lateinit var thumb: ImageView
    private lateinit var count: TextView
    private lateinit var shutter: ImageButton
    private lateinit var permissionBox: View

    private var imageCapture: ImageCapture? = null
    private var takenHere = 0
    private var processing = 0
    private var lastMarked: String? = null
    private var askedOnce = false

    // 줌
    private var camera: Camera? = null
    private lateinit var zoomText: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var zoomPresets: LinearLayout
    private lateinit var focusRing: View
    private val presetViews = mutableListOf<Pair<Float, TextView>>()
    private var sliderTouching = false

    private val pickAlbum = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        processing += uris.size
        updateCount()
        Toast.makeText(this, "앨범 사진 ${uris.size}장 가져오는 중…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        AlbumImport.importPhotos(app, plan, pin, uris) { ok, fail ->
            processing -= uris.size
            takenHere += ok
            if (fail > 0) Toast.makeText(app, "${fail}장은 가져오지 못했습니다", Toast.LENGTH_SHORT).show()
            if (isDestroyed) return@importPhotos
            updateCount()
            pin.photos.lastOrNull()?.let { lastMarked = it.marked; loadThumb(it.marked) }
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionBox()
    }

    /** 휴대폰을 돌려 찍어도 사진 방향이 맞도록 (화면은 세로 고정) */
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plan = Repo.plan(this, intent.getStringExtra(EXTRA_PLAN)) ?: run { finish(); return }
        pin = plan.pins.find { it.id == intent.getStringExtra(EXTRA_PIN) } ?: run { finish(); return }
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preview = findViewById(R.id.preview)
        flash = findViewById(R.id.flash)
        thumb = findViewById(R.id.thumb)
        count = findViewById(R.id.count)
        shutter = findViewById(R.id.shutter)
        permissionBox = findViewById(R.id.permission_box)
        zoomText = findViewById(R.id.zoom_text)
        zoomBadge = findViewById(R.id.zoom_badge)
        zoomSlider = findViewById(R.id.zoom_slider)
        zoomPresets = findViewById(R.id.zoom_presets)
        focusRing = findViewById(R.id.focus_ring)
        setupZoomControls()
        findViewById<Button>(R.id.album).setOnClickListener {
            pickAlbum.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        val coord = PhotoProcessor.coordOf(this, plan, pin)
        val dir = pin.dir?.let { "  ·  방향 " + PinMarker.dirText(it) } ?: ""
        findViewById<TextView>(R.id.info).text = "핀 ${pin.label}  ·  ${coord.mainText()}$dir\n${plan.name}"

        shutter.setOnClickListener { shoot() }
        findViewById<Button>(R.id.done).setOnClickListener { finishWithResult() }
        thumb.setOnClickListener { lastMarked?.let { Repo.viewImage(this, Repo.photoFile(this, it)) } }
        findViewById<Button>(R.id.grant).setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) || !askedOnce) {
                askedOnce = true
                requestCamera.launch(Manifest.permission.CAMERA)
            } else {
                // "다시 묻지 않음" 상태면 앱 설정 화면으로
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        findViewById<Button>(R.id.use_system).setOnClickListener {
            setResult(RESULT_USE_SYSTEM)
            finish()
        }
        updateCount()

        if (hasPermission()) startCamera() else { askedOnce = true; requestCamera.launch(Manifest.permission.CAMERA) }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한을 켜고 돌아온 경우
        if (permissionBox.visibility == View.VISIBLE && hasPermission()) startCamera()
    }

    override fun onStart() {
        super.onStart()
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onStop() {
        super.onStop()
        orientation.disable()
    }

    private fun showPermissionBox() {
        permissionBox.visibility = View.VISIBLE
        shutter.isEnabled = false
    }

    private fun startCamera() {
        permissionBox.visibility = View.GONE
        shutter.isEnabled = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                imageCapture = ic
                camera = cam
                onCameraReady(cam)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
                showPermissionBox()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- 줌 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoomControls() {
        // 두 손가락으로 확대/축소
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                setZoom(z.zoomRatio * d.scaleFactor)
                return true
            }
        })
        var pinched = false
        preview.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pinched = false
                MotionEvent.ACTION_POINTER_DOWN -> pinched = true
            }
            // 한 손가락으로 짧게 누르면 초점 (확대/축소 동작 뒤에는 무시)
            if (e.actionMasked == MotionEvent.ACTION_UP && !pinched) {
                focusAt(e.x, e.y)
                v.performClick()
            }
            true
        }
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(p / 1000f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { sliderTouching = true }
            override fun onStopTrackingTouch(sb: SeekBar) { sliderTouching = false }
        })
    }

    private fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio))
    }

    /** 카메라가 열리면 지원 배율에 맞춰 배율 버튼을 만들고, 줌 상태를 화면에 반영 */
    private fun onCameraReady(cam: Camera) {
        val state = cam.cameraInfo.zoomState
        val first = state.value
        val min = first?.minZoomRatio ?: 1f
        val max = first?.maxZoomRatio ?: 1f
        zoomPresets.removeAllViews()
        presetViews.clear()
        val d = resources.displayMetrics.density
        for (r in listOf(0.5f, 1f, 2f, 5f, 10f)) {
            if (r < min - 0.01f || r > max + 0.01f) continue
            val tv = TextView(this).apply {
                text = if (r < 1f) ".5" else "${r.toInt()}×"
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(this@CameraActivity, R.drawable.zoom_chip)
                setOnClickListener { setZoom(r) }
            }
            val lp = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt()
            }
            zoomPresets.addView(tv, lp)
            presetViews += r to tv
        }
        // 줌을 지원하지 않는 카메라면 줌 조절을 숨김
        val canZoom = max > min + 0.01f
        zoomPresets.visibility = if (presetViews.size > 1) View.VISIBLE else View.GONE
        (zoomSlider.parent as View).visibility = if (canZoom) View.VISIBLE else View.GONE

        state.removeObservers(this)
        state.observe(this) { z ->
            val ratio = z.zoomRatio
            val label = String.format(Locale.US, "%.1f×", ratio)
            zoomText.text = label
            if (!sliderTouching) zoomSlider.progress = (z.linearZoom * 1000).toInt()
            // 지금 배율과 같은 배율 버튼을 흰 원으로 강조
            for ((r, tv) in presetViews) {
                val sel = kotlin.math.abs(r - ratio) < r * 0.05f
                tv.isSelected = sel
                tv.setTextColor(if (sel) 0xFF1F2328.toInt() else 0xFFFFFFFF.toInt())
            }
            showZoomBadge(label)
        }
    }

    private var badgeShownOnce = false
    private fun showZoomBadge(label: String) {
        // 처음 열릴 때는 표시하지 않음
        if (!badgeShownOnce) { badgeShownOnce = true; return }
        zoomBadge.text = label
        zoomBadge.animate().cancel()
        zoomBadge.alpha = 1f
        zoomBadge.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
    }

    /** 누른 곳에 초점과 노출 맞추기 */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val point = preview.meteringPointFactory.createPoint(x, y)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point).setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS).build()
            )
            focusRing.x = x - focusRing.width / 2f
            focusRing.y = y - focusRing.height / 2f
            focusRing.animate().cancel()
            focusRing.alpha = 1f
            focusRing.scaleX = 1.3f; focusRing.scaleY = 1.3f
            focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
                focusRing.animate().alpha(0f).setStartDelay(600).setDuration(300).start()
            }.start()
        } catch (_: Exception) {
        }
    }

    // ---------- 촬영 ----------

    private fun shoot() {
        val ic = imageCapture ?: return
        val time = System.currentTimeMillis()
        val coord = PhotoProcessor.coordOf(this, plan, pin)
        val path = Repo.newOriginalPath(this, plan, pin, time, coord)
        val file = Repo.photoFile(this, path)

        // 셔터 효과
        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(180).start()

        processing++
        updateCount()
        val app = applicationContext
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    PhotoSaver.process(app, plan, pin, path, time) { photo, failed ->
                        processing--
                        takenHere++
                        if (isDestroyed) return@process
                        if (failed) Toast.makeText(app, "좌표 사진을 만들지 못해 원본만 저장했습니다", Toast.LENGTH_SHORT).show()
                        lastMarked = photo.marked
                        updateCount()
                        loadThumb(photo.marked)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    processing--
                    file.delete()
                    updateCount()
                    Toast.makeText(app, "촬영하지 못했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun updateCount() {
        val wait = if (processing > 0) "\n저장 중 $processing" else ""
        count.text = "이번에 ${takenHere}장\n이 핀 총 ${pin.photos.size}회$wait"
    }

    private fun loadThumb(rel: String) {
        val f = Repo.photoFile(this, rel)
        PhotoSaver.worker.execute {
            val bmp = PhotoProcessor.decodeRotated(f, 300)
            runOnUiThread { if (!isDestroyed && bmp != null) thumb.setImageBitmap(bmp) }
        }
    }

    /** 볼륨 버튼으로도 촬영 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && imageCapture != null) {
            if (event?.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun finishWithResult() {
        if (processing > 0) Toast.makeText(this, "남은 사진 ${processing}장은 계속 저장됩니다", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }
}
