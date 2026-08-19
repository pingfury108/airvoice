package com.airvoice.ime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * silero-vad 流式端点检测：PCM 流入，完整句子流出。
 * 非线程安全——调用方保证单线程访问（采集协程 + flush 前 join）。
 */
class VadProcessor(assets: AssetManager) {

    private val vad: Vad
    private var pending = FloatArray(0)

    init {
        vad = Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelPaths.VAD,
                    threshold = 0.35f,           // 偏低更灵敏，轻声也能触发
                    minSilenceDuration = 0.4f,   // 句间停顿 0.4s 触发切句
                    minSpeechDuration = 0.2f,
                    maxSpeechDuration = 5f,      // 长句强制切分，保证出字延迟有界
                    windowSize = WINDOW,
                ),
                sampleRate = AsrEngine.SAMPLE_RATE,
                numThreads = 1,
                debug = false,
            ),
        )
    }

    /** 喂入任意长度 PCM，返回检测到的完整句子 */
    fun accept(chunk: FloatArray): List<FloatArray> {
        pending += chunk
        val segments = mutableListOf<FloatArray>()
        while (pending.size >= WINDOW) {
            vad.acceptWaveform(pending.copyOfRange(0, WINDOW))
            pending = pending.copyOfRange(WINDOW, pending.size)
            while (!vad.empty()) {
                segments.add(vad.front().samples)
                vad.pop()
            }
        }
        return segments
    }

    /** 录音结束时收尾：补零对齐后强制吐出残留语音段 */
    fun flush(): List<FloatArray> {
        if (pending.isNotEmpty()) {
            vad.acceptWaveform(pending.copyOf(WINDOW)) // copyOf 自动补零
            pending = FloatArray(0)
        }
        vad.flush()
        val segments = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            segments.add(vad.front().samples)
            vad.pop()
        }
        vad.reset()
        return segments
    }

    /** 取消录音时丢弃全部状态 */
    fun reset() {
        pending = FloatArray(0)
        vad.reset()
    }

    companion object {
        const val WINDOW = 512 // silero-vad 固定窗长，32ms @16kHz
    }
}
