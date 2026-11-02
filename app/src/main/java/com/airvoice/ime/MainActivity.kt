package com.airvoice.ime

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 首次启动引导页：权限 → 启用输入法 → 切换输入法 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var etToken: EditText
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
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

        // 局域网识别 API
        etToken = findViewById(R.id.etToken)
        etToken.setText(prefs.getString(Prefs.KEY_API_TOKEN, "") ?: "")
        val swApi = findViewById<Switch>(R.id.swApi)
        swApi.isChecked = prefs.getBoolean(Prefs.KEY_API_ENABLED, false)
        swApi.setOnCheckedChangeListener { _, checked ->
            saveToken()
            prefs.edit().putBoolean(Prefs.KEY_API_ENABLED, checked).apply()
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                }
                ResidentService.start(this)
            } else {
                ResidentService.stop(this)
            }
            refreshStatus()
        }
        findViewById<Button>(R.id.btnCopyApi).setOnClickListener {
            val url = ResidentService.lanUrl() ?: "（无局域网地址）"
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("airvoice", url),
            )
            Toast.makeText(this, "已复制 $url", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 自愈：开关打开但进程被杀（重装/系统回收）时，重新拉起常驻服务
        if (prefs.getBoolean(Prefs.KEY_API_ENABLED, false)) {
            runCatching { ResidentService.start(this) }
        }
    }

    override fun onPause() {
        saveToken()
        super.onPause()
    }

    private fun saveToken() {
        if (::etToken.isInitialized) {
            prefs.edit().putString(Prefs.KEY_API_TOKEN, etToken.text.toString().trim()).apply()
        }
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

        val apiOn = prefs.getBoolean(Prefs.KEY_API_ENABLED, false)
        statusText.text = buildString {
            append(if (modelReady) "✓" else "✗").append(" 识别模型").append('\n')
            append(if (audioGranted) "✓" else "✗").append(" 录音权限").append('\n')
            append(if (imeEnabled) "✓" else "✗").append(" 输入法已启用").append('\n')
            append(if (batteryUnrestricted) "✓" else "✗").append(" 电池优化已忽略（防杀进程）").append('\n')
            append("ℹ 识别语言：$langLabel\n")
            append("ℹ API：")
            append(if (apiOn) (ResidentService.lanUrl() ?: "启动中/无局域网") else "已关闭")
        }

        findViewById<TextView>(R.id.tvApiAddr).text =
            "地址：" + (if (apiOn) (ResidentService.lanUrl() ?: "等待网络…") else "未启动")

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
        private const val REQ_NOTIFY = 2
    }
}
