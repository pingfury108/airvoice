package com.airvoice.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：API 开关打开时拉起常驻服务（系统限制导致失败时静默，用户手动打开 App 即可） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_API_ENABLED, false)
        if (on) {
            try {
                ResidentService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
