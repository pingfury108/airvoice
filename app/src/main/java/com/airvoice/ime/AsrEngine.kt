package com.airvoice.ime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封装 sherpa-onnx 离线识别器（SenseVoice-Small）。
 * 所有推理串行在单线程 dispatcher 上执行。
 */
class AsrEngine(private val assets: AssetManager) {

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    var isReady = false
        private set

    suspend fun init() = withContext(dispatcher) {
        if (recognizer != null) return@withContext
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = ModelPaths.SENSE_VOICE,
                    useInverseTextNormalization = true,
                ),
                tokens = ModelPaths.TOKENS,
                numThreads = 2,
                provider = "cpu",
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(assetManager = assets, config = config)
        isReady = true
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
    }
}
