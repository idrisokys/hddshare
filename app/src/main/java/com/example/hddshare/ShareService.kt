package com.example.hddshare

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Hafif bir bekçi servisi: açılışta başlar, USB disk var mı diye bakar.
 * Disk VARSA HTTP/WebDAV sunucusunu açar, disk YOKSA sunucu kapalı kalır (port dinlenmez).
 * Disk çıkarılınca sunucu kapanır, tekrar takılınca kendiliğinden açılır.
 */
class ShareService : Service() {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var server: FileServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var lastText: String? = null
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                syncServer()
            } catch (e: Exception) {
                Log.e(TAG, "Kontrol hatası", e)
            }
            handler?.postDelayed(this, Config.CHECK_INTERVAL_MS)
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Takma/çıkarma olayında hemen kontrol et (kısa gecikme: mount bitsin)
            Volumes.invalidate()
            handler?.removeCallbacks(tick)
            handler?.postDelayed(tick, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HDD Paylaşım", NotificationManager.IMPORTANCE_MIN)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification(lastText ?: "Disk bekleniyor"))
        } catch (e: Exception) {
            // Android 12+: uygulama arka plandayken foreground servis başlatılamayabilir
            Log.e(TAG, "startForeground başarısız", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            val t = HandlerThread("hddshare-watch").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h

            val f = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            registerReceiver(mediaReceiver, f)
            h.post(tick)
        }
        return START_STICKY
    }

    @Synchronized
    private fun syncServer() {
        val hasDisk = Volumes.list(applicationContext).isNotEmpty()
        if (hasDisk && server == null) startServer()
        else if (!hasDisk && server != null) stopServer()
        updateNotification()
    }

    @Synchronized
    private fun startServer() {
        try {
            val s = FileServer(applicationContext)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
        } catch (e: Exception) {
            // Port meşgul olabilir; bir sonraki kontrolde tekrar denenecek
            Log.e(TAG, "Sunucu başlatılamadı", e)
            return
        }

        val wl = wifiLock ?: createWifiLock().also { wifiLock = it }
        if (!wl.isHeld) wl.acquire()

        if (Config.KEEP_CPU_AWAKE) {
            val l = wakeLock ?: newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hddshare:cpu").also { wakeLock = it }
            if (!l.isHeld) l.acquire()
        }

        // Cihazın (ekran/bekleme modu) uykuya geçmesini engelle; uyku, USB diski ve Wi-Fi'ı da keser
        if (Config.KEEP_SCREEN_ON) {
            @Suppress("DEPRECATION")
            val l = screenLock ?: newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "hddshare:screen").also { screenLock = it }
            if (!l.isHeld) l.acquire()
        }
    }

    @Suppress("DEPRECATION")
    private fun createWifiLock(): WifiManager.WifiLock =
        applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hddshare:wifi")
            .apply { setReferenceCounted(false) }

    private fun newWakeLock(level: Int, tag: String): PowerManager.WakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(level, tag)
            .apply { setReferenceCounted(false) }

    @Synchronized
    private fun stopServer() {
        try { server?.stop() } catch (e: Exception) { Log.e(TAG, "Durdurma hatası", e) }
        server = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        screenLock?.takeIf { it.isHeld }?.release()
    }

    private fun updateNotification() {
        val text = if (server != null) "Paylaşım açık - port ${Config.PORT}" else "Disk bekleniyor"
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HDD Paylaş")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()

    // Uygulama son uygulamalardan kapatılırsa sistem servisi de öldürebilir; 1,5 sn sonra yeniden başlat
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val pi = PendingIntent.getForegroundService(
                this, 1, Intent(this, ShareService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
        } catch (e: Exception) {
            Log.e(TAG, "Yeniden başlatma kurulamadı", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(mediaReceiver) } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        stopServer()
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HDDShare"
        private const val CHANNEL_ID = "share"
        private const val NOTIF_ID = 1
    }
}
