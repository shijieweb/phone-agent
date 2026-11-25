package net.de5.phoneagent

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 「视频转音乐」页（v0.7.0-pad12）—— 给手机主人用的一页，不是给操作员。
 *
 * 老板的原话是这个功能要让妈妈能自己用：「丢进去 → 点一下 → 好了」。所以这一页
 * 只有三件事：告诉她放哪个文件夹、有几个等着转、按一下。技术细节（编码器、采样率、
 * 跳过判据）全部藏在 [Mp3Plan] / [Mp3Runner] 里，这一页一个字都不提。
 *
 * 不导出的第二个 Activity（[android:exported] = false，不进最近任务）：只有配置页
 * 那个「视频转音乐」按钮能进来，外部没有入口，也就没有别人能替她按下转换。
 *
 * 线程：扫描和转换都在后台线程跑，界面只经 [uiHandler] 回主线程改文字。
 * 转换期间把开始按钮禁掉——重复点会同时跑两批，两批抢同一个 .part 文件。
 */
class ConvertActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var progressView: TextView
    private lateinit var resultView: TextView
    private lateinit var startButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())

    /** 转换是否在跑。主线程读写，只用来挡住重复点。 */
    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = UiText.CONVERT

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(40), dp(40), dp(40), dp(40))
        scroll.addView(root)

        addLabel(root, "源目录")
        val dirView = TextView(this)
        dirView.text = Mp3Plan.DEFAULT_DIR
        dirView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        dirView.setTextIsSelectable(true)
        dirView.setPadding(0, dp(4), 0, dp(20))
        root.addView(dirView, matchWidth())

        addLabel(root, "视频放这里，转换出来的 mp3 也在同一个文件夹")
        addLabel(root, "认这些后缀: " + TextUtils.join(" ", Mp3Plan.EXTENSIONS.sorted()))

        summaryView = TextView(this)
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        summaryView.setPadding(0, dp(20), 0, dp(16))
        root.addView(summaryView, matchWidth())

        startButton = addButton(root, START_LABEL) { start() }

        progressView = TextView(this)
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        progressView.setPadding(0, dp(16), 0, dp(8))
        root.addView(progressView, matchWidth())

        resultView = TextView(this)
        resultView.typeface = Typeface.MONOSPACE
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        resultView.setTextIsSelectable(true)
        root.addView(resultView, matchWidth())

        addButton(root, "返回") { finish() }

        setContentView(scroll)
        refresh()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * 重新数一遍：待转几个、跳过几个、无音轨几个。
     *
     * 每次回到这一页都重数（[onCreate] 调一次），因为主人可能是先去放文件再回来。
     */
    private fun refresh() {
        summaryView.text = "正在统计…"
        startButton.isEnabled = false
        Thread({
            val scanned = Mp3Converter.scan(Mp3Plan.DEFAULT_DIR)
            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                val error = scanned.error
                if (error != null) {
                    summaryView.text = Mp3Plan.failure(error)
                    startButton.isEnabled = false
                    return@post
                }
                val rows = scanned.rows
                val pending = Mp3Plan.pendingCount(rows)
                summaryView.text = if (rows.isEmpty()) {
                    "这个文件夹里还没有视频"
                } else {
                    "视频 " + rows.size + " 个：待转 " + pending +
                        " 个，已有 mp3 " + Mp3Plan.existingCount(rows) +
                        " 个，无音轨 " + Mp3Plan.noAudioCount(rows) + " 个"
                }
                startButton.isEnabled = pending > 0 && !running
                startButton.text = if (pending > 0) START_LABEL else "没有要转的"
            }
        }, "mp3-scan").start()
    }

    /**
     * 按一下：后台扫一遍再真转。
     *
     * 重新扫而不是复用 refresh 的结果：主人可能刚删了几个文件，拿旧清单去转会
     * 在日志里留下一串「文件不存在」。扫一遍只要几十毫秒。
     */
    private fun start() {
        if (running) return
        running = true
        startButton.isEnabled = false
        resultView.text = ""
        progressView.text = "准备中…"

        Thread({
            val scanned = Mp3Converter.scan(Mp3Plan.DEFAULT_DIR)
            val error = scanned.error
            if (error != null) {
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    running = false
                    progressView.text = ""
                    summaryView.text = Mp3Plan.failure(error)
                    startButton.isEnabled = false
                }
                return@Thread
            }
            val rows = scanned.rows
            if (Mp3Plan.pendingCount(rows) == 0) {
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    running = false
                    progressView.text = ""
                    summaryView.text = "没有要转的视频"
                    startButton.isEnabled = false
                }
                return@Thread
            }

            val outcome = Mp3Runner.run(Mp3Plan.DEFAULT_DIR, rows, force = false) { line ->
                // 进度只显示最新一行（主线程），全量清单在结束时一次性铺开，
                // 免得每 10% 都往主线程塞一次长文本。
                uiHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    progressView.text = line
                }
            }

            uiHandler.post {
                if (isFinishing || isDestroyed) return@post
                running = false
                // 全量清单里最后一行就是汇总（完成/失败/跳过/无音轨/用时），
                // 所以这里不再另写一句，免得两处数字将来对不上。
                resultView.text = outcome.lines.joinToString("\n")
                progressView.text = ""
                // 重数一遍：刚转出来的 mp3 已经在盘上，这一页要立刻反映「没有要转的」。
                refresh()
            }
        }, "mp3-run").start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun addLabel(parent: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        view.setTextColor(Color.parseColor("#757575"))
        view.setPadding(0, dp(8), 0, 0)
        parent.addView(view, matchWidth())
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = text
        button.isAllCaps = false
        button.setOnClickListener { onClick() }
        parent.addView(button, matchWidth())
        return button
    }

    private companion object {
        /**
         * 按钮文字。故意不用 [UiText.CONVERT]：那是配置页上「进入这一页」的按钮，
         * 这里是页内的「开始」，两个都叫「视频转音乐」会让 click apply 分不清。
         */
        const val START_LABEL = "开始转换"
    }
}
