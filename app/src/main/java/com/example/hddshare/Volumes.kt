package com.example.hddshare

import android.content.Context
import android.os.Environment
import java.io.File

/** Takılı ve bağlanmış (mounted) harici USB depolama birimlerini her çağrıda yeniden bulur. */
object Volumes {

    private fun usable(f: File): Boolean {
        if (!f.isDirectory) return false
        val st = try { Environment.getExternalStorageState(f) } catch (e: Exception) { Environment.MEDIA_UNKNOWN }
        // Sistem "bağlı" diyorsa diske ekstra dokunma (listFiles bozuk dosya okunurken
        // geçici olarak null/boş dönüp sunucuyu yanlışlıkla kapatıyordu).
        if (st == Environment.MEDIA_MOUNTED || st == Environment.MEDIA_MOUNTED_READ_ONLY) return true
        if (st != Environment.MEDIA_UNKNOWN) return false
        return f.canRead() && f.listFiles() != null
    }

    fun list(ctx: Context): List<File> {
        val result = linkedMapOf<String, File>()

        File("/storage").listFiles()?.forEach { f ->
            if (f.name != "emulated" && f.name != "self" && usable(f)) {
                result[f.name] = f
            }
        }

        try {
            ctx.getExternalFilesDirs(null).forEach { d ->
                if (d == null) return@forEach
                if (!Environment.isExternalStorageRemovable(d)) return@forEach
                val p = d.absolutePath
                val i = p.indexOf("/Android/data")
                if (i > 0) {
                    val root = File(p.substring(0, i))
                    if (root.name.isNotEmpty() && usable(root)) result[root.name] = root
                }
            }
        } catch (_: Exception) {
        }
        return result.values.toList()
    }
}
