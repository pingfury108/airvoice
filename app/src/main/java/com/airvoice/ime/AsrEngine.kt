package com.airvoice.ime

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封装 sherpa-onnx 离线识别器（SenseVoice-Small）。
 * 所有推理串行在单线程 dispatcher 上执行（保证提交顺序）。
 */
class AsrEngine(private val assets: AssetManager) {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    var isReady = false
        private set

    @Volatile
    var language: String = ""
        private set

    /**
     * 初始化或按语言重建识别器。
     * @param language ""=auto 自动检测, "zh"/"yue"/"en"/"ja"/"ko" 强制语种
     */
    suspend fun init(language: String = "") = withContext(dispatcher) {
        if (recognizer != null && this@AsrEngine.language == language) return@withContext
        recognizer = null
        isReady = false
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = ModelPaths.SENSE_VOICE,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = ModelPaths.TOKENS,
                numThreads = 4,
                provider = "cpu",
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
        this@AsrEngine.language = language
        isReady = true
        Log.i(TAG, "recognizer ready, language=${language.ifEmpty { "auto" }}")
    }

    /** 整段识别。samples 为 16kHz float PCM */
    suspend fun recognize(samples: FloatArray): String = withContext(dispatcher) {
        val rec = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer = null
        isReady = false
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "AirVoice"
    }
}
