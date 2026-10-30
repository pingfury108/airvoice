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
    const val KEY_FLOAT_X = "float_x"   // 横屏悬浮组件位置
    const val KEY_FLOAT_Y = "float_y"
    const val KEY_API_ENABLED = "api_enabled" // 局域网识别 API 开关（默认关）
    const val KEY_API_TOKEN = "api_token"     // X-Token，空=不鉴权
}
