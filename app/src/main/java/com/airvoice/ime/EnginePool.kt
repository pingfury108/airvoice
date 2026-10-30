package com.airvoice.ime

import android.content.Context

/**
 * 进程级共享识别引擎：IME（输入法）与 ResidentService（HTTP API）共用同一实例，
 * 避免 500MB 模型双份常驻。AsrEngine 内部已串行化所有推理（单线程 dispatcher），
 * IME 与 API 的并发请求会自动排队，无需额外加锁。
 */
object EnginePool {

    @Volatile
    private var engine: AsrEngine? = null

    /** 幂等获取：按需创建并按语言（重）初始化；语言相同且已就绪时零开销返回 */
    suspend fun ensure(context: Context, lang: String): AsrEngine {
        val e = engine ?: synchronized(this) {
            engine ?: AsrEngine(context.assets).also { engine = it }
        }
        e.init(lang)
        return e
    }

    fun tryReady(): AsrEngine? = engine?.takeIf { it.isReady }

    /** 统一识别入口（确保引擎就绪后识别） */
    suspend fun recognize(context: Context, lang: String, samples: FloatArray): String =
        ensure(context, lang).recognize(samples)
}
