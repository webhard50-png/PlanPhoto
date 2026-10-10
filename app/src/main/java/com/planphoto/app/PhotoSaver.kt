package com.planphoto.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 찍은 원본으로 좌표 사진을 만들고 핀에 기록한다.
 * 화면을 닫아도 처리가 끝까지 진행되도록 앱 전체에서 하나의 작업 줄을 쓴다.
 */
object PhotoSaver {
    val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun process(app: Context, plan: Plan, pin: Pin, path: String, time: Long, done: (Photo, Boolean) -> Unit) {
        worker.execute {
            val coord = PhotoProcessor.coordOf(app, plan, pin)
            var failed = false
            val result = try {
                PhotoProcessor.process(app, plan, pin, path, time, coord)
            } catch (e: Throwable) {
                // 좌표 사진을 못 만들어도 원본은 남긴다
                failed = true
                Repo.exportToGallery(app, Repo.photoFile(app, path), plan, pin, time)
                Photo(path, path, time, coord.nameX, coord.nameY, coord.mainText())
            }
            main.post {
                pin.photos.add(result)
                Repo.save(app)
                done(result, failed)
            }
        }
    }
}
