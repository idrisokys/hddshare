package io.github.idris.ters

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ters", Context.MODE_PRIVATE)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        root.addView(status)

        fun btn(text: String, action: () -> Unit): View = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        root.addView(btn("1) Uygulama seç") { pickApp() })
        root.addView(btn("2) Üstte gösterme izni ver") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("3) Kullanım erişimi izni ver") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(btn("4) Başlat") { startFlip() })
        root.addView(btn("Durdur") {
            stopService(Intent(this, FlipService::class.java))
            Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
        })
        root.addView(btn("Seçili uygulamayı aç") { launchSelected() })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun selectedLabel(): String {
        val pkg = prefs.getString("pkg", null) ?: return "(seçilmedi)"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    private fun refresh() {
        status.text = "Seçili uygulama: ${selectedLabel()}\n" +
            "Üstte gösterme izni: ${if (Settings.canDrawOverlays(this)) "✓" else "✗"}\n" +
            "Kullanım erişimi: ${if (hasUsageAccess()) "✓" else "✗"}"
    }

    private fun pickApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("Baş aşağı gösterilecek uygulama")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                prefs.edit().putString("pkg", apps[i].second).apply()
                refresh()
            }
            .show()
    }

    private fun startFlip() {
        if (prefs.getString("pkg", null) == null) {
            Toast.makeText(this, "Önce uygulama seç", Toast.LENGTH_SHORT).show(); return
        }
        if (!Settings.canDrawOverlays(this) || !hasUsageAccess()) {
            Toast.makeText(this, "İki izni de ver", Toast.LENGTH_SHORT).show(); return
        }
        startForegroundService(Intent(this, FlipService::class.java))
        launchSelected()
    }

    private fun launchSelected() {
        val pkg = prefs.getString("pkg", null) ?: return
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }
}
