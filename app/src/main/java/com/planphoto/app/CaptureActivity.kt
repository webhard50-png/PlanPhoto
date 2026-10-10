package com.planphoto.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 핀을 누르면 앱 안 카메라(CameraActivity)를 바로 연다.
 * 카메라 권한을 주지 않은 경우에만 휴대폰 기본 카메라 앱으로 찍는다.
 * 어느 쪽이든 원본 + 좌표 사진 두 장이 저장된다.
 */
abstract class CaptureActivity : AppCompatActivity() {

    protected val worker: ExecutorService = Executors.newSingleThreadExecutor()

    // 앱 안 카메라로 보낸 핀
    private var camPlanId: String? = null
    private var camPinId: String? = null

    // 기본 카메라 앱으로 찍는 중인 사진
    private var pendingPlanId: String? = null
    private var pendingPinId: String? = null
    private var pendingPath: String? = null
    private var pendingTime = 0L

    private val openCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val plan = Repo.plan(this, camPlanId)
        val pin = plan?.pins?.find { it.id == camPinId }
        camPlanId = null; camPinId = null
        if (plan == null || pin == null) return@registerForActivityResult
        if (res.resultCode == CameraActivity.RESULT_USE_SYSTEM) captureWithSystemCamera(plan, pin)
        else onPhotoSaved(pin)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onSystemCaptureResult(ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            camPlanId = it.getString("cam_plan")
            camPinId = it.getString("cam_pin")
            pendingPlanId = it.getString("cap_plan")
            pendingPinId = it.getString("cap_pin")
            pendingPath = it.getString("cap_path")
            pendingTime = it.getLong("cap_time")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cam_plan", camPlanId)
        outState.putString("cam_pin", camPinId)
        outState.putString("cap_plan", pendingPlanId)
        outState.putString("cap_pin", pendingPinId)
        outState.putString("cap_path", pendingPath)
        outState.putLong("cap_time", pendingTime)
    }

    /** 핀 촬영: 앱 안 카메라를 바로 연다 */
    protected fun capturePhoto(plan: Plan, pin: Pin) {
        camPlanId = plan.id
        camPinId = pin.id
        openCamera.launch(
            Intent(this, CameraActivity::class.java)
                .putExtra(CameraActivity.EXTRA_PLAN, plan.id)
                .putExtra(CameraActivity.EXTRA_PIN, pin.id)
        )
    }

    private fun captureWithSystemCamera(plan: Plan, pin: Pin) {
        val time = System.currentTimeMillis()
        val coord = PhotoProcessor.coordOf(this, plan, pin)
        val path = Repo.newOriginalPath(this, plan, pin, time, coord)
        pendingPlanId = plan.id
        pendingPinId = pin.id
        pendingPath = path
        pendingTime = time
        try {
            takePicture.launch(Repo.uriFor(this, Repo.photoFile(this, path)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "카메라 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show()
            Repo.photoFile(this, path).delete()
            clearPending()
        }
    }

    private fun clearPending() {
        pendingPlanId = null; pendingPinId = null; pendingPath = null; pendingTime = 0L
    }

    private fun onSystemCaptureResult(ok: Boolean) {
        val path = pendingPath ?: return
        val plan = Repo.plan(this, pendingPlanId)
        val pin = plan?.pins?.find { it.id == pendingPinId }
        val file = Repo.photoFile(this, path)
        val time = pendingTime
        clearPending()
        if (!ok || plan == null || pin == null || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
        Toast.makeText(this, "좌표 사진 만드는 중…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        PhotoSaver.process(app, plan, pin, path, time) { _, failed ->
            val msg = if (failed) "좌표 사진을 만들지 못해 원본만 저장했습니다"
            else "핀 ${pin.label}: 원본 + 좌표 사진 저장 (총 ${pin.photos.size}회)"
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
            if (!isDestroyed) onPhotoSaved(pin)
        }
    }

    protected abstract fun onPhotoSaved(pin: Pin)

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
