package com.example.hddshare

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val ui = Handler(Looper.getMainLooper())
    // Disk/ağ taraması ana iş parçacığını bloklamasın
    private val io = Executors.newSingleThreadExecutor()
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

        if (!hasStoragePermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        } else {
            requestBatteryExemption()
        }
        try {
            startForegroundService(Intent(this, ShareService::class.java))
        } catch (_: Exception) {
        }
    }

    // Geri tuşu uygulamayı kapatmasın, sadece arka plana atsın (servis zaten ayrı çalışır)
    @Deprecated("Deprecated in Java")
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

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        Volumes.invalidate()
        refresh()
        requestBatteryExemption()
    }

    private fun hasStoragePermission() =
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

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

    // Wi-Fi / Ethernet arayüzlerini öne al, sonra ilk IPv4'ü döndür
    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun refresh() {
        try {
            io.execute {
                val vols = Volumes.list(this)
                val ip = if (vols.isEmpty()) null else localIp()
                runOnUiThread { render(vols, ip) }
            }
        } catch (_: Exception) {
        }
    }

    private fun render(vols: List<File>, ip: String?) {
        tv.text = buildString {
            if (vols.isEmpty()) {
                append("Disk bekleniyor - paylaşım KAPALI\n\n")
            } else {
                append("Paylaşım AÇIK\n\n")
                append("Adres: http://${ip ?: "IP bulunamadı"}:${Config.PORT}\n\n")
            }
            if (!hasStoragePermission()) append("Depolama izni VERİLMEDİ. İzin ver, yoksa disk görünmez.\n\n")
            if (!isBatteryExempt()) append("Pil optimizasyonu AÇIK. Stick ayarlarından bu uygulama için kapat.\n\n")
            append(
                if (vols.isEmpty()) "Takılı harici disk bulunamadı."
                else "Bulunan diskler:\n" + vols.joinToString("\n") { "  " + it.absolutePath }
            )
            append("\n\nBu ekranı kapatabilirsin. Stick açılınca servis kendiliğinden başlar; disk takılıysa paylaşır, değilse sunucu açılmaz.")
        }
    }
}
