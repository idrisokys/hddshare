package com.example.hddshare

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import java.io.File

/**
 * Takılı ve bağlanmış (mounted) harici USB depolama birimlerini bulur.
 * Sonuç kısa süre önbelleğe alınır; her HTTP isteğinde diski yeniden taramak yerine
 * en fazla [TTL_MS] eski liste kullanılır. Takma/çıkarma olayında [invalidate] çağrılır.
 */
object Volumes {
    private const val TTL_MS = 2000L

    @Volatile private var cached: List<File> = emptyList()
    @Volatile private var stamp = -TTL_MS

    fun invalidate() {
        stamp = -TTL_MS
    }

    fun list(ctx: Context): List<File> {
        if (SystemClock.elapsedRealtime() - stamp < TTL_MS) return cached
        return synchronized(this) {
            if (SystemClock.elapsedRealtime() - stamp < TTL_MS) {
                cached
            } else {
                scan(ctx).also {
                    cached = it
                    stamp = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun usable(f: File): Boolean {
        if (!f.isDirectory || !f.canRead()) return false
        val st = try {
            Environment.getExternalStorageState(f)
        } catch (e: Exception) {
            Environment.MEDIA_UNKNOWN
        }
        val stateOk = st == Environment.MEDIA_MOUNTED ||
            st == Environment.MEDIA_MOUNTED_READ_ONLY ||
            st == Environment.MEDIA_UNKNOWN
        // Okunamayan / yarı çıkmış birimleri ele (list() listFiles()'tan daha ucuz)
        return stateOk && f.list() != null
    }

    private fun scan(ctx: Context): List<File> {
        val result = linkedMapOf<String, File>()

        File("/storage").listFiles()?.forEach { f ->
            if (f.name != "emulated" && f.name != "self" && usable(f)) {
                result[f.name] = f
            }
        }

        try {
            ctx.getExternalFilesDirs(null).forEach { d ->
                if (d == null || !Environment.isExternalStorageRemovable(d)) return@forEach
                val p = d.absolutePath
                val i = p.indexOf("/Android/data")
                if (i > 0) {
                    val root = File(p.substring(0, i))
                    if (root.name.isNotEmpty() && root.name !in result && usable(root)) result[root.name] = root
                }
            }
        } catch (_: Exception) {
        }
        return result.values.toList()
    }
}
