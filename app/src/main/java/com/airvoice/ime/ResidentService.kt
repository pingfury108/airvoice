package com.airvoice.ime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** 常驻前台服务：挂载局域网识别 API（模型随进程常驻，调用零加载延迟） */
class ResidentService : Service() {

    private var server: AsrServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "识别 API", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        server?.stop()
        server = null
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val s = AsrServer(applicationContext, AsrServer.PORT, p.getString(Prefs.KEY_API_TOKEN, "") ?: "")
        try {
            s.start(5000, true)
            server = s
            Log.i(TAG, "api server started on :${AsrServer.PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "api server start failed", e)
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFY_ID, buildNotification())
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("空音识别 API")
            .setContentText(lanUrl() ?: "等待网络…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AirVoice"
        private const val CHANNEL_ID = "api"
        private const val NOTIFY_ID = 1

        /** 局域网 IPv4 → http://ip:port（无网络返回 null） */
        fun lanUrl(): String? = try {
            val ip = NetworkInterface.getNetworkInterfaces().asSequence()
                .filterNotNull()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence().filterNotNull() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
            ip?.let { "http://$it:${AsrServer.PORT}" }
        } catch (_: Exception) {
            null
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ResidentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ResidentService::class.java))
        }
    }
}
