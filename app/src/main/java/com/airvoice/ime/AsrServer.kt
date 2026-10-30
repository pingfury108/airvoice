package com.airvoice.ime

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

/**
 * 局域网识别 API：
 *   GET  /health              → {"ready":true|false}
 *   POST /recognize [?fmt=pcm] → {"text":"...","ms":820}
 * 鉴权：请求头 X-Token（token 非空时校验）。忙时返回 409。
 * 仅监听入站连接，无任何出站请求。
 */
class AsrServer(
    private val context: Context,
    port: Int,
    private val token: String,
) : NanoHTTPD("0.0.0.0", port) {

    @Volatile
    private var busy = false

    override fun serve(session: IHTTPSession): Response {
        if (token.isNotEmpty() && session.headers["x-token"] != token) {
            return json(Response.Status.UNAUTHORIZED, """{"error":"bad token"}""")
        }
        return when (session.uri) {
            "/health" -> json(Response.Status.OK, """{"ready":${EnginePool.tryReady() != null}}""")
            "/recognize" -> handleRecognize(session)
            else -> json(Response.Status.NOT_FOUND, """{"error":"not found"}""")
        }
    }

    private fun handleRecognize(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(Response.Status.BAD_REQUEST, err("use POST"))
        }
        if (busy) {
            return json(Response.Status.CONFLICT, err("busy, retry later"))
        }
        // NanoHTTPD 的 inputStream 不按 content-length 截断，必须按头精确读，否则等到超时
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return json(Response.Status.BAD_REQUEST, err("empty body"))
        if (len > MAX_UPLOAD_BYTES) {
            return json(Response.Status.PAYLOAD_TOO_LARGE, err("body too large"))
        }
        val bytes = try {
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = session.inputStream.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            if (read != len) throw java.io.IOException("short read: $read/$len")
            buf
        } catch (e: Exception) {
            Log.w(TAG, "read body failed: ${e.message}")
            return json(Response.Status.BAD_REQUEST, err("read body failed"))
        }

        val fmt = session.parameters["fmt"]?.firstOrNull() ?: "wav"
        val raw = when (fmt) {
            "pcm" -> pcm16ToFloat(bytes)
            else -> parseWav(bytes)
                ?: return json(Response.Status.BAD_REQUEST, err("need WAV(PCM16) body, or ?fmt=pcm for raw s16le/16k/mono"))
        }
        if (raw.isEmpty()) return json(Response.Status.BAD_REQUEST, err("no audio samples"))
        // 与 IME 一致：最长 60s，超出截断
        val samples = if (raw.size > MAX_SAMPLES) raw.copyOf(MAX_SAMPLES) else raw

        busy = true
        try {
            val lang = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(Prefs.KEY_LANGUAGE, "") ?: ""
            val t0 = System.currentTimeMillis()
            val text = runBlocking { EnginePool.recognize(context, lang, samples) }
            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "api recognize %.1fs -> %dms".format(samples.size / AsrEngine.SAMPLE_RATE.toFloat(), ms))
            return json(Response.Status.OK, """{"text":"${jsonEscape(text)}","ms":$ms}""")
        } catch (e: Exception) {
            Log.e(TAG, "api recognize failed", e)
            return json(Response.Status.INTERNAL_ERROR, err("recognize failed"))
        } finally {
            busy = false
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun err(msg: String) = """{"error":"$msg"}"""

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    companion object {
        private const val TAG = "AirVoice"
        const val PORT = 8787
        private const val MAX_UPLOAD_BYTES = 16 * 1024 * 1024
        private const val MAX_SAMPLES = AsrEngine.SAMPLE_RATE * 60

        private fun le16(b: ByteArray, off: Int) =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, off: Int) = le16(b, off) or (le16(b, off + 2) shl 16)

        private fun fourcc(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)

        /** s16le 裸 PCM → float */
        internal fun pcm16ToFloat(b: ByteArray): FloatArray {
            val n = b.size / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                out[i] = le16(b, i * 2).toShort().toFloat() / 32768f
            }
            return out
        }

        /** 解析 WAV（PCM16，任意采样率/单双声道）→ 16kHz mono float；不支持返回 null */
        internal fun parseWav(b: ByteArray): FloatArray? {
            if (b.size < 12 || fourcc(b, 0) != "RIFF" || fourcc(b, 8) != "WAVE") return null
            var off = 12
            var channels = 0
            var rate = 0
            var dataStart = -1
            var dataLen = 0
            while (off + 8 <= b.size) {
                val id = fourcc(b, off)
                var size = le32(b, off + 4)
                when (id) {
                    "fmt " -> {
                        if (off + 8 + 16 > b.size) return null
                        val format = le16(b, off + 8)
                        channels = le16(b, off + 10)
                        rate = le32(b, off + 12)
                        val bits = le16(b, off + 22)
                        if (format != 1 || bits != 16) return null
                    }
                    "data" -> {
                        dataStart = off + 8
                        // 录音软件常见流式头：size 全 0xFF，按剩余字节处理
                        if (size <= 0 || dataStart + size > b.size) size = b.size - dataStart
                        dataLen = size
                        break
                    }
                }
                off += 8 + size + (size and 1) // RIFF 字对齐
            }
            if (dataStart < 0 || channels !in 1..2 || rate <= 0) return null
            val frames = dataLen / (2 * channels)
            if (frames <= 0) return null

            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0
                for (ch in 0 until channels) {
                    val p = dataStart + (i * channels + ch) * 2
                    acc += le16(b, p).toShort().toInt()
                }
                mono[i] = acc / channels / 32768f
            }
            return if (rate == AsrEngine.SAMPLE_RATE) mono else resample(mono, rate, AsrEngine.SAMPLE_RATE)
        }

        /** 线性插值重采样（离线识别够用） */
        private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
            val n = (x.size.toLong() * to / from).toInt()
            val y = FloatArray(n)
            val step = from.toDouble() / to
            var pos = 0.0
            for (i in 0 until n) {
                val i0 = pos.toInt()
                val frac = (pos - i0).toFloat()
                val i1 = minOf(i0 + 1, x.size - 1)
                y[i] = x[i0] * (1 - frac) + x[i1] * frac
                pos += step
            }
            return y
        }
    }
}
