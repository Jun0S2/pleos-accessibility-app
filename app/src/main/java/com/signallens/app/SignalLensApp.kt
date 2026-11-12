package com.signallens.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 앱 전체에서 하나만 있는 두뇌(AppController).
 * 화면을 닫고 지도로 돌아가도 차 신호를 계속 보고, 말하고, 지도 위에 카드를 띄운다.
 */
class SignalLensApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
        controller.start()
    }
}

/**
 * 앱 화면이 안 보일 때도 감시를 계속하도록 붙잡아 두는 서비스 (포그라운드 서비스).
 */
class SignalLensService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Signal Lens 주행 감시", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Signal Lens")
            .setContentText("차 신호를 보고 있어요")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // 포그라운드가 안 돼도 앱 프로세스가 살아 있는 동안은 동작한다
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "signal_lens_watch"
    }
}
