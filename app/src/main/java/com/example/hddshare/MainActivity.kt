package com.example.hddshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(80, 80, 80, 80)
        }
        setContentView(tv)
        window.decorView.setBackgroundColor(Color.BLACK)
        if (Config.KEEP_SCREEN_ON) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        } else {
            requestBatteryExemption()
        }
        startForegroundService(Intent(this, ShareService::class.java))
    }

    // Geri tuşu uygulamayı kapatmasın, sadece arka plana atsın (servis zaten ayrı çalışır)
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        ui.post(loop)
    }

    override fun onPause() {
        ui.removeCallbacks(loop)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
        requestBatteryExemption()
    }

    private fun isBatteryExempt(): Boolean = try {
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        false
    }

    // Pil optimizasyonunu kapatma isteği. Stick'te bu ekran yoksa sessizce atlanır; elle yapılabilir.
    private fun requestBatteryExemption() {
        try {
            if (isBatteryExempt()) return
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            if (i.resolveActivity(packageManager) != null) startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun refresh() {
        val granted = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val vols = Volumes.list(this)
        tv.text = buildString {
            if (vols.isEmpty()) {
                append("Disk bekleniyor - paylaşım KAPALI\n\n")
            } else {
                append("Paylaşım AÇIK\n\n")
                append("Adres: http://${localIp() ?: "IP bulunamadı"}:${Config.PORT}\n\n")
            }
            if (!granted) append("Depolama izni VERİLMEDİ. İzin ver, yoksa disk görünmez.\n\n")
            if (!isBatteryExempt()) append("Pil optimizasyonu AÇIK. Stick ayarlarından bu uygulama için kapat.\n\n")
            append(if (vols.isEmpty()) "Takılı harici disk bulunamadı." else "Bulunan diskler:\n" + vols.joinToString("\n") { "  " + it.absolutePath })
            append("\n\nBu ekranı kapatabilirsin. Stick açılınca servis kendiliğinden başlar; disk takılıysa paylaşır, değilse sunucu açılmaz.")
        }
    }
}
