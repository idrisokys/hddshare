package io.github.idris.ters

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Seçili uygulama öne gelince 1x1 görünmez bir overlay penceresi ekler ve
 * ekran yönünü REVERSE_PORTRAIT (180°) yapar. Başka uygulamaya geçince overlay kalkar.
 * Erişilebilirlik servisi KULLANILMAZ.
 */
class FlipService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var usm: UsageStatsManager
    private var overlay: View? = null
    private var current: String? = null
    private var lastQuery = 0L

    private val tick = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        lastQuery = System.currentTimeMillis() - 10_000

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("ters", "Ters Ekran", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "ters")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Ters Ekran çalışıyor")
            .build()
        startForeground(1, n)
        handler.post(tick)
    }

    @Suppress("DEPRECATION")
    private fun poll() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> current = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (current == e.packageName) current = null
            }
        }
        lastQuery = now - 2000

        val selected = getSharedPreferences("ters", Context.MODE_PRIVATE).getString("pkg", null)
        if (selected != null && current == selected) showOverlay() else hideOverlay()
    }

    private fun showOverlay() {
        if (overlay != null) return
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        val v = View(this)
        try {
            wm.addView(v, lp)
            overlay = v
        } catch (_: Exception) { }
    }

    private fun hideOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        overlay = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
