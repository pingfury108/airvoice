package com.airvoice.ime

import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.util.Log
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

class VoiceInputService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var asr: AsrEngine

    private var recorder: AudioRecorder? = null
    private var collectJob: Job? = null
    private val pcmChunks = mutableListOf<FloatArray>()
    private var cancelled = false

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var enterButton: Button

    override fun onCreate() {
        super.onCreate()
        asr = AsrEngine(assets)
        serviceScope.launch {
            try {
                Log.i(TAG, "model init start")
                asr.init()
                Log.i(TAG, "model init done, ready=${asr.isReady}")
                setStatus(if (asr.isReady) "就绪，按住说话" else "模型加载失败")
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
                    // 上滑取消
                    cancelled = event.y < -CANCEL_DISTANCE_PX
                    setStatus(if (cancelled) "松开取消" else "录音中… 上滑取消")
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecordingAndRecognize()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelled = true
                    stopRecordingAndRecognize()
                    true
                }
                else -> false
            }
        }
        setupEditKeys(view)
        return view
    }

    /** 基础编辑键：切换/收起/光标/标点/空格/删除(按住连删)/智能回车 */
    private fun setupEditKeys(view: View) {
        enterButton = view.findViewById(R.id.btnEnter)
        view.findViewById<Button>(R.id.btnHide).setOnClickListener { requestHideSelf(0) }
        view.findViewById<Button>(R.id.btnComma).setOnClickListener {
            currentInputConnection?.commitText("，", 1)
        }
        view.findViewById<Button>(R.id.btnPeriod).setOnClickListener {
            currentInputConnection?.commitText("。", 1)
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
        collectJob = serviceScope.launch {
            var total = 0
            rec.chunks.collect { chunk ->
                if (total < MAX_SAMPLES) {
                    pcmChunks.add(chunk)
                    total += chunk.size
                }
            }
        }
        setStatus("录音中… 上滑取消")
        micButton.text = "松手结束 · 上滑取消"
    }

    private fun stopRecordingAndRecognize() {
        val rec = recorder ?: return
        recorder = null
        collectJob?.cancel()
        collectJob = null
        rec.stop()

        if (cancelled) {
            pcmChunks.clear()
            setStatus("已取消")
            micButton.text = getString(R.string.mic_hint)
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
        micButton.text = getString(R.string.mic_hint)
        serviceScope.launch {
            val text = asr.recognize(samples)
            if (text.isNotEmpty()) {
                currentInputConnection?.commitText(text, 1)
                setStatus("已上屏")
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
