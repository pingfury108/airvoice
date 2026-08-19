package com.airvoice.ime

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView

/** 首次启动引导页：权限 → 启用输入法 → 切换输入法 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var langButtons: Map<Int, String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_PERMISSION)
        }
        findViewById<Button>(R.id.btnEnableIme).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        // 识别语言锁定（空串 = auto 自动检测）
        langButtons = mapOf(
            R.id.btnLangAuto to "",
            R.id.btnLangZh to "zh",
            R.id.btnLangYue to "yue",
            R.id.btnLangEn to "en",
        )
        for ((id, lang) in langButtons) {
            findViewById<Button>(id).setOnClickListener {
                getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                    .edit().putString(Prefs.KEY_LANGUAGE, lang).apply()
                refreshStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val audioGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val modelReady = ModelPaths.ready(this)
        // targetSdk 34 禁读 Settings.Secure.ENABLED_INPUT_METHODS，改用 IMM API（可见自身）
        val imeEnabled = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val batteryUnrestricted = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        val lang = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
            .getString(Prefs.KEY_LANGUAGE, "") ?: ""
        val langLabel = when (lang) {
            "zh" -> "中文"
            "yue" -> "粤语"
            "en" -> "English"
            else -> "自动"
        }

        statusText.text = buildString {
            append(if (modelReady) "✓" else "✗").append(" 识别模型").append('\n')
            append(if (audioGranted) "✓" else "✗").append(" 录音权限").append('\n')
            append(if (imeEnabled) "✓" else "✗").append(" 输入法已启用").append('\n')
            append(if (batteryUnrestricted) "✓" else "✗").append(" 电池优化已忽略（防杀进程）").append('\n')
            append("ℹ 识别语言：$langLabel")
        }

        // 语言选中高亮
        for ((id, l) in langButtons) {
            val btn = findViewById<Button>(id)
            if (l == lang) {
                btn.setBackgroundResource(R.drawable.bg_key_enter)
                btn.setTextColor(0xFF3D5AFE.toInt())
            } else {
                btn.setBackgroundResource(R.drawable.bg_key)
                btn.setTextColor(0xFF374151.toInt())
            }
        }
    }

    companion object {
        private const val REQ_PERMISSION = 1
    }
}
