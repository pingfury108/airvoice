package com.airvoice.ime

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class VoiceInputService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private lateinit var asr: AsrEngine

    private var recorder: AudioRecorder? = null
    private var collectJob: Job? = null
    private val pcmChunks = mutableListOf<FloatArray>()
    private var cancelled = false
    private val commitStack = ArrayDeque<Int>() // 每次上屏长度，用于撤回

    private var statusText: TextView? = null // 横屏悬浮球布局无此视图
    private lateinit var micButton: Button
    private var enterButton: Button? = null
    private var isLandscape = false

    override fun onCreate() {
        super.onCreate()
        asr = AsrEngine(assets)
        serviceScope.launch {
            try {
                Log.i(TAG, "model init start")
                asr.init(prefs.getString(Prefs.KEY_LANGUAGE, "") ?: "")
                Log.i(TAG, "model init done")
                setStatus("就绪，按住说话")
            } catch (e: Exception) {
                Log.e(TAG, "model init failed", e)
                setStatus("模型加载失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun onCreateInputView(): View = buildInputView()

    /** 横屏禁用系统 extract mode（全屏编辑条） */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != isLandscape && ::micButton.isInitialized) {
            setInputView(buildInputView())
        }
    }

    private fun buildInputView(): View {
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val view = layoutInflater.inflate(R.layout.input_view, null) // 横屏自动用 layout-land
        statusText = view.findViewById(R.id.statusText)
        micButton = view.findViewById(R.id.micButton)

        if (isLandscape) {
            setupBall()
            view.post { applyBallWindow() }
        } else {
            micButton.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelled = false
                        startRecording()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        cancelled = event.y < -CANCEL_DISTANCE_PX
                        if (cancelled) setStatus("松开取消")
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        stopRecordingAndRecognize(cancelled)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stopRecordingAndRecognize(cancel = true)
                        true
                    }
                    else -> false
                }
            }
            restoreDockedWindow()
        }
        setupEditKeys(view)
        setupKeyPanel(view)
        syncStatus()
        return view
    }

    /** 按键面板（终端友好）：⌨ 键切换，🎤 键切回 */
    private fun setupKeyPanel(view: View) {
        val voicePanel = view.findViewById<View>(R.id.voicePanel) ?: return
        val keyPanel = view.findViewById<LinearLayout>(R.id.keyPanel) ?: return
        view.findViewById<Button>(R.id.btnKeyPanel)?.let { toggle ->
            toggle.setOnClickListener {
                val showKeys = keyPanel.visibility != View.VISIBLE
                voicePanel.visibility = if (showKeys) View.GONE else View.VISIBLE
                keyPanel.visibility = if (showKeys) View.VISIBLE else View.GONE
                toggle.text = if (showKeys) "🎤" else "⌨"
            }
        }
        KeyPanel(this, object : KeyPanel.Listener {
            override fun onText(text: String) {
                currentInputConnection?.commitText(text, 1)
            }

            override fun onKey(keyCode: Int, ctrl: Boolean, alt: Boolean) {
                val ic = currentInputConnection ?: return
                var meta = 0
                if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                val now = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
                ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
            }

            override fun onDelete() = deleteOneChar()
            override fun onEnter() = performEnter()
        }).build(keyPanel)
    }

    /** 视图创建时同步状态：模型可能早于视图就绪，避免一直停在 XML 默认的“加载中…” */
    private fun syncStatus() {
        when {
            !ModelPaths.ready(this) -> setStatus("模型缺失：请先执行 scripts/download-deps.sh 并重新安装")
            !::asr.isInitialized || !asr.isReady -> setStatus("模型加载中…")
            else -> setStatus("就绪，按住说话")
        }
    }

    /** 横屏悬浮组件：窗口尺寸定死（🎤64 + 间距8 + ⌫64），不依赖 ROM 对 WRAP_CONTENT 的处理 */
    private fun applyBallWindow() {
        val dialog = window?.window ?: return
        val dm = resources.displayMetrics
        val width = dp(FLOAT_W_DP)
        val height = dp(FLOAT_H_DP)
        val attrs = dialog.attributes
        attrs.width = width
        attrs.height = height
        attrs.gravity = Gravity.TOP or Gravity.START
        val defX = dm.widthPixels - width - dp(24)
        val defY = dm.heightPixels - height - dp(160)
        attrs.x = prefs.getInt(Prefs.KEY_FLOAT_X, defX).coerceIn(0, maxOf(0, dm.widthPixels - width))
        attrs.y = prefs.getInt(Prefs.KEY_FLOAT_Y, defY).coerceIn(0, maxOf(0, dm.heightPixels - height))
        dialog.attributes = attrs
        dialog.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 系统弹出 IME 窗口时可能重置属性，重放一次
        if (isLandscape && ::micButton.isInitialized) micButton.post { applyBallWindow() }
    }

    /** 悬浮球手势：点按=开始/停止录音，拖动=移动(记忆位置)，长按=收起键盘 */
    private fun setupBall() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dragging = false
        var longPressed = false
        var downRawX = 0f
        var downRawY = 0f
        var startWX = 0
        var startWY = 0
        var pendingX = 0
        var pendingY = 0
        var lastWindowUpdate = 0L
        micButton.setOnLongClickListener {
            longPressed = true
            requestHideSelf(0)
            true
        }
        micButton.setOnTouchListener { _, event ->
            val dialog = window?.window ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    longPressed = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWX = dialog.attributes.x
                    startWY = dialog.attributes.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        pendingX = startWX + dx.toInt()
                        pendingY = startWY + dy.toInt()
                        // 节流窗口重排：每次 MOVE 都 setAttributes 会导致整窗闪烁
                        if (event.eventTime - lastWindowUpdate > 40) {
                            lastWindowUpdate = event.eventTime
                            val a = dialog.attributes
                            a.x = pendingX
                            a.y = pendingY
                            dialog.attributes = a
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val a = dialog.attributes
                        a.x = pendingX
                        a.y = pendingY
                        dialog.attributes = a
                        prefs.edit()
                            .putInt(Prefs.KEY_FLOAT_X, pendingX)
                            .putInt(Prefs.KEY_FLOAT_Y, pendingY)
                            .apply()
                    } else if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        // 点按切换录音状态（录音中拖动不影响录音）
                        if (recorder != null) {
                            stopRecordingAndRecognize(cancel = false)
                        } else {
                            cancelled = false
                            startRecording()
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** 竖屏恢复底部停靠：横屏改过窗口属性（悬浮坐标），旋转回来必须显式还原 */
    private fun restoreDockedWindow() {
        val dialog = window?.window ?: return
        val attrs = dialog.attributes
        attrs.width = WindowManager.LayoutParams.MATCH_PARENT
        attrs.height = WindowManager.LayoutParams.WRAP_CONTENT
        attrs.gravity = Gravity.BOTTOM or Gravity.FILL_HORIZONTAL
        attrs.x = 0
        attrs.y = 0
        dialog.attributes = attrs
        dialog.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 基础编辑键：切换/收起/光标/标点/空格/删除(按住连删)/撤回/智能回车。横屏悬浮球无这些键，全部空安全绑定 */
    private fun setupEditKeys(view: View) {
        enterButton = view.findViewById(R.id.btnEnter)
        view.findViewById<Button>(R.id.btnHide)?.setOnClickListener { requestHideSelf(0) }
        view.findViewById<Button>(R.id.btnComma)?.apply {
            setOnClickListener { currentInputConnection?.commitText("，", 1) }
            setOnLongClickListener { currentInputConnection?.commitText("、", 1); true }
        }
        view.findViewById<Button>(R.id.btnPeriod)?.apply {
            setOnClickListener { currentInputConnection?.commitText("。", 1) }
            setOnLongClickListener { currentInputConnection?.commitText("！", 1); true }
        }
        view.findViewById<Button>(R.id.btnLeft)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        view.findViewById<Button>(R.id.btnRight)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        view.findViewById<Button>(R.id.btnUp)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
        }
        view.findViewById<Button>(R.id.btnDown)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        view.findViewById<Button>(R.id.btnSpace)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<Button>(R.id.btnSwitchIme)?.setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        view.findViewById<Button>(R.id.btnUndo)?.setOnClickListener {
            val len = commitStack.removeLastOrNull()
            if (len != null) currentInputConnection?.deleteSurroundingText(len, 0)
        }
        enterButton?.setOnClickListener { performEnter() }

        var repeatJob: Job? = null
        view.findViewById<Button>(R.id.btnDelete)?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteOneChar()
                    repeatJob = serviceScope.launch {
                        delay(400)
                        while (isActive) {
                            deleteOneChar()
                            delay(50)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatJob?.cancel()
                    true
                }
                else -> false
            }
        }
    }

    private fun deleteOneChar() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    /** 智能回车：微信=发送、浏览器=搜索、备忘录=换行，随目标 App 的 EditorInfo 自适应 */
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        if (enterButton != null) {
            enterButton?.text = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
                EditorInfo.IME_ACTION_SEND -> "发送"
                EditorInfo.IME_ACTION_SEARCH -> "搜索"
                EditorInfo.IME_ACTION_GO -> "前往"
                EditorInfo.IME_ACTION_NEXT -> "下一项"
                EditorInfo.IME_ACTION_DONE -> "完成"
                else -> "⏎"
            }
        }
        // 语言设置变化时重建识别器
        val lang = prefs.getString(Prefs.KEY_LANGUAGE, "") ?: ""
        if (::asr.isInitialized && asr.isReady && lang != asr.language) {
            setStatus("切换识别语言…")
            serviceScope.launch {
                try {
                    asr.init(lang)
                    setStatus("就绪，按住说话")
                } catch (e: Exception) {
                    Log.e(TAG, "language switch failed", e)
                    setStatus("模型加载失败: ${e.message}")
                }
            }
        }
    }

    private fun performEnter() {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (action != EditorInfo.IME_ACTION_NONE && !noAction) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun startRecording() {
        if (!ModelPaths.ready(this)) {
            setStatus("模型缺失：请先执行 scripts/download-deps.sh 并重新安装")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("缺少录音权限，请打开空音 App 授权")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!asr.isReady) {
            setStatus("模型加载中…")
            return
        }

        pcmChunks.clear()
        val rec = AudioRecorder()
        recorder = rec
        rec.start(serviceScope)
        setStatus("🎤 录音中… 上滑取消")
        micButton.text = if (isLandscape) "🎤" else "松手结束 · 上滑取消"
        micButton.setBackgroundResource(if (isLandscape) R.drawable.bg_mic_ball_recording else R.drawable.bg_mic_recording)

        collectJob = serviceScope.launch(Dispatchers.Default) {
            var total = 0
            var tick = 0
            rec.chunks.collect { chunk ->
                if (total >= MAX_SAMPLES) return@collect
                pcmChunks.add(chunk)
                total += chunk.size

                if (++tick % 3 == 0) { // ~120ms 刷新一次音量条
                    val rms = sqrt(chunk.fold(0.0) { acc, v -> acc + v * v } / chunk.size)
                    val level = (rms * 40).toInt().coerceIn(1, 10)
                    launch(Dispatchers.Main) {
                        setStatus("🎤 " + "▮".repeat(level) + "▯".repeat(10 - level))
                    }
                }
            }
        }
    }

    private fun stopRecordingAndRecognize(cancel: Boolean) {
        val rec = recorder ?: return
        recorder = null
        rec.stop()
        collectJob?.cancel()
        collectJob = null
        micButton.text = if (isLandscape) "🎤" else getString(R.string.mic_hint)
        micButton.setBackgroundResource(if (isLandscape) R.drawable.bg_mic_ball else R.drawable.bg_mic)

        if (cancel) {
            pcmChunks.clear()
            setStatus("已取消")
            return
        }

        val total = pcmChunks.sumOf { it.size }
        if (total < AsrEngine.SAMPLE_RATE / 4) { // 少于 0.25s 视为误触
            pcmChunks.clear()
            setStatus("说话时间太短")
            return
        }

        val samples = FloatArray(total)
        var offset = 0
        for (chunk in pcmChunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        pcmChunks.clear()

        setStatus("识别中…")
        if (isLandscape) micButton.setBackgroundResource(R.drawable.bg_mic_ball_processing)
        serviceScope.launch {
            val t0 = System.currentTimeMillis()
            val text = asr.recognize(samples)
            val cost = System.currentTimeMillis() - t0
            Log.i(TAG, "utterance %.1fs, infer %dms: %s".format(samples.size / 16000f, cost, text))
            if (isLandscape) micButton.setBackgroundResource(R.drawable.bg_mic_ball)
            if (text.isNotEmpty()) {
                currentInputConnection?.commitText(text, 1)
                commitStack.addLast(text.length)
                setStatus("就绪，按住说话")
            } else {
                setStatus("未识别到内容")
            }
        }
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    override fun onDestroy() {
        recorder?.stop()
        asr.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirVoice"
        private const val MAX_SAMPLES = AsrEngine.SAMPLE_RATE * 60 // 最长 60s
        private const val CANCEL_DISTANCE_PX = 150
        private const val FLOAT_W_DP = 64     // 横屏悬浮胶囊窗口宽（56胶囊+两侧阴影余量）
        private const val FLOAT_H_DP = 112    // 胶囊 100dp + 阴影余量
    }
}
