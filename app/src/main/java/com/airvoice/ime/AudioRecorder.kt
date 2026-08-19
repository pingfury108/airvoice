package com.airvoice.ime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** AudioRecord 采集 16kHz/16bit/单声道 PCM，以 FloatArray 块形式输出 */
class AudioRecorder {

    val chunks = MutableSharedFlow<FloatArray>(extraBufferCapacity = 128)

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission") // 权限在启动前已校验
    fun start(scope: CoroutineScope) {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(AsrEngine.SAMPLE_RATE, channel, format)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, // 原始麦克风；VOICE_COMMUNICATION 的降噪会吃掉轻声语音
            AsrEngine.SAMPLE_RATE, channel, format, minBuf * 2,
        )
        audioRecord = record
        record.startRecording()

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1280) // 40ms
            while (isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) chunks.tryEmit(pcm16ToFloat(buf, n))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        audioRecord?.runCatching { stop() }
        audioRecord?.release()
        audioRecord = null
    }

    private fun pcm16ToFloat(buf: ByteArray, len: Int): FloatArray {
        val out = FloatArray(len / 2)
        for (i in out.indices) {
            val lo = buf[i * 2].toInt() and 0xFF
            val hi = buf[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }
}
