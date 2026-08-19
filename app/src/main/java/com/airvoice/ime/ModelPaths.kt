package com.airvoice.ime

import android.content.Context

/** assets 内模型路径约定 */
object ModelPaths {
    const val SENSE_VOICE = "models/model.int8.onnx"
    const val TOKENS = "models/tokens.txt"
    const val VAD = "models/silero_vad.onnx" // M2 伪流式使用

    fun ready(context: Context): Boolean = listOf(SENSE_VOICE, TOKENS).all { path ->
        runCatching { context.assets.open(path).close() }.isSuccess
    }
}

/** SharedPreferences 约定 */
object Prefs {
    const val NAME = "settings"
    const val KEY_LANGUAGE = "language" // ""=auto, zh/yue/en
}
