package com.airvoice.ime

import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var enterButton: Button

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

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)
        statusText = view.findViewById(R.id.statusText)
        micButton = view.findViewById(R.id.micButton)

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
        setupEditKeys(view)
        return view
    }

    /** 基础编辑键：切换/收起/光标/标点/空格/删除(按住连删)/撤回/智能回车 */
    private fun setupEditKeys(view: View) {
        enterButton = view.findViewById(R.id.btnEnter)
        view.findViewById<Button>(R.id.btnHide).setOnClickListener { requestHideSelf(0) }
        view.findViewById<Button>(R.id.btnComma).apply {
            setOnClickListener { currentInputConnection?.commitText("，", 1) }
            setOnLongClickListener { currentInputConnection?.commitText("、", 1); true }
        }
        view.findViewById<Button>(R.id.btnPeriod).apply {
            setOnClickListener { currentInputConnection?.commitText("。", 1) }
            setOnLongClickListener { currentInputConnection?.commitText("！", 1); true }
        }
        view.findViewById<Button>(R.id.btnLeft).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        view.findViewById<Button>(R.id.btnRight).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        view.findViewById<Button>(R.id.btnUp).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
        }
        view.findViewById<Button>(R.id.btnDown).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        view.findViewById<Button>(R.id.btnSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        view.findViewById<Button>(R.id.btnUndo).setOnClickListener {
            val len = commitStack.removeLastOrNull()
            if (len != null) currentInputConnection?.deleteSurroundingText(len, 0)
        }
        enterButton.setOnClickListener { performEnter() }

        var repeatJob: Job? = null
        view.findViewById<Button>(R.id.btnDelete).setOnTouchListener { _, event ->
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
        if (::enterButton.isInitialized) {
            enterButton.text = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
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
        micButton.text = "松手结束 · 上滑取消"
        micButton.setBackgroundResource(R.drawable.bg_mic_recording)

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
        micButton.text = getString(R.string.mic_hint)
        micButton.setBackgroundResource(R.drawable.bg_mic)

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
        serviceScope.launch {
            val t0 = System.currentTimeMillis()
            val text = asr.recognize(samples)
            val cost = System.currentTimeMillis() - t0
            Log.i(TAG, "utterance %.1fs, infer %dms: %s".format(samples.size / 16000f, cost, text))
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
        if (::statusText.isInitialized) statusText.text = text
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
    }
}
