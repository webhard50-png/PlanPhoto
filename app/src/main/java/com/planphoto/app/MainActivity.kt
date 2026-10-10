package com.planphoto.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var adapter: PlanAdapter

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPdf(it) }
    }

    /** Android 10: 저장 권한 */
    private val requestLegacyStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, if (ok) "사진을 내장 메모리/PDFPIC 에 저장합니다" else "권한이 없어 사진/PDFPIC 에 저장합니다", Toast.LENGTH_SHORT).show()
    }

    private fun askStorage(force: Boolean) = PdfPicStorage.ask(this, force) {
        requestLegacyStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "저장 폴더 (PDFPIC)")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 1) { askStorage(force = true); return true }
        return super.onOptionsItemSelected(item)
    }

    /** PDF 교체 대상 도면 id (파일 선택 화면 동안 화면이 다시 만들어져도 유지) */
    private var replaceTarget: String? = null
    private val pickReplace = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val plan = Repo.plan(this, replaceTarget)
        replaceTarget = null
        if (uri != null && plan != null) PdfReplace.run(this, plan, uri) { adapter.notifyDataSetChanged() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("replace", replaceTarget)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        replaceTarget = savedInstanceState?.getString("replace")
        setContentView(R.layout.activity_main)

        val list = findViewById<ListView>(R.id.plan_list)
        list.emptyView = findViewById<View>(R.id.empty_text)
        adapter = PlanAdapter()
        list.adapter = adapter
        // 파일을 누르면: 열기 / PDF 교체 / 내보내기 / 이름 변경 / 공유 / 삭제
        list.setOnItemClickListener { _, _, pos, _ -> planMenu(adapter.getItem(pos)) }
        list.setOnItemLongClickListener { _, _, pos, _ -> planMenu(adapter.getItem(pos)); true }

        findViewById<Button>(R.id.import_button).setOnClickListener {
            pickPdf.launch(arrayOf("application/pdf"))
        }

        if (savedInstanceState == null) {
            handleIncoming(intent)
            askStorage(force = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** 다른 앱에서 PDF를 "다음으로 열기"/공유로 보낸 경우 */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamUri(intent)
            else -> null
        }
        uri?.let { importPdf(it) }
        intent?.action = null // 화면 회전 등으로 다시 불러오지 않게
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    private fun importPdf(uri: Uri) {
        Toast.makeText(this, "새 파일 만드는 중…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        worker.execute {
            try {
                val plan = Repo.importPdf(app, uri)
                runOnUiThread { adapter.notifyDataSetChanged(); openPlan(plan) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, e.message ?: "새 파일을 만들지 못했습니다", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun openPlan(plan: Plan) {
        startActivity(Intent(this, PlanActivity::class.java).putExtra(PlanActivity.EXTRA_PLAN, plan.id))
    }

    private fun planMenu(plan: Plan) {
        AlertDialog.Builder(this)
            .setTitle(plan.name)
            .setItems(arrayOf("도면 열기 (핀·촬영)", "내보내기 (PDF + 사진 ZIP)", "평면도 PDF 교체", "이름 변경", "사진 전체 공유", "삭제")) { _, which0 ->
                when (which0) {
                    0 -> { openPlan(plan); return@setItems }
                    2 -> {
                        PdfReplace.confirm(this, plan) {
                            replaceTarget = plan.id
                            pickReplace.launch(arrayOf("application/pdf"))
                        }
                        return@setItems
                    }
                    1 -> { Exporter.start(this, plan); return@setItems }
                }
                val which = which0 - 3
                when (which) {
                    0 -> {
                        Names.askName(this, "도면 이름 변경", plan.name, "예: 101동 3층 평면도") { v ->
                            plan.name = v.removeSuffix(".pdf").removeSuffix(".PDF")
                            Repo.save(this); adapter.notifyDataSetChanged()
                        }
                    }
                    1 -> Repo.sharePhotos(this, plan.pins.flatMap { it.photos })
                    2 -> AlertDialog.Builder(this)
                        .setTitle("'${plan.name}' 삭제")
                        .setMessage("핀 ${plan.pins.size}개와 앱 안의 사진 ${plan.photoCount}회분이 삭제됩니다.\nPDFPIC 폴더의 사진은 남습니다.")
                        .setPositiveButton("삭제") { _, _ -> Repo.deletePlan(this, plan); adapter.notifyDataSetChanged() }
                        .setNegativeButton("취소", null).show()
                }
            }
            .show()
    }

    private inner class PlanAdapter : BaseAdapter() {
        private val plans get() = Repo.plans(this@MainActivity)
        override fun getCount() = plans.size
        override fun getItem(position: Int) = plans[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_plan, parent, false)
            val p = getItem(position)
            v.findViewById<TextView>(R.id.plan_name).text = p.name + ".pdf"
            v.findViewById<TextView>(R.id.plan_info).text =
                "핀 ${p.pins.size}개 · 촬영 ${p.photoCount}회 · " +
                    if (p.revision > 0) "도면 ${p.revision}차 교체 ${Repo.dayFormat.format(Date(p.replacedAt))}"
                    else Repo.dayFormat.format(Date(p.createdAt))
            return v
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
