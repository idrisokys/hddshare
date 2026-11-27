package com.example.hddshare

import android.content.Context
import android.net.Uri
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Salt okunur HTTP + WebDAV (PROPFIND) sunucusu.
 * Kodi: "Web server directory (HTTP)" veya "WebDAV directory (HTTP)" olarak eklenebilir.
 * Range desteği vardır, yani ileri-geri sarma çalışır.
 */
class FileServer(private val ctx: Context) : NanoHTTPD(Config.PORT) {

    private object MultiStatus : Response.IStatus {
        override fun getRequestStatus(): Int = 207
        override fun getDescription(): String = "207 Multi-Status"
    }

    private sealed class Target {
        object Root : Target()
        class Node(val file: File) : Target()
    }

    private class Entry(val name: String, val dir: Boolean, val size: Long, val modified: Long)

    override fun serve(session: IHTTPSession): Response {
        return try {
            handle(session)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Sunucu hatası")
        }
    }

    private fun handle(session: IHTTPSession): Response {
        if (!authorized(session)) {
            val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Yetkisiz")
            r.addHeader("WWW-Authenticate", "Basic realm=\"HDD\"")
            return r
        }
        val method = session.method
        if (method == Method.OPTIONS) {
            val r = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            r.addHeader("DAV", "1")
            r.addHeader("Allow", "OPTIONS, GET, HEAD, PROPFIND")
            return r
        }

        val path = session.uri
        val target = resolve(path)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Bulunamadı")
        val isDir = target is Target.Root || (target is Target.Node && target.file.isDirectory)

        return when (method) {
            Method.PROPFIND -> propfind(session, path, target, isDir)
            Method.GET, Method.HEAD ->
                if (isDir) listing(path, target) else sendFile(session, (target as Target.Node).file)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "İzin yok")
        }
    }

    // ---------- yardımcılar ----------

    private fun authorized(s: IHTTPSession): Boolean {
        if (Config.PASSWORD.isEmpty()) return true
        val h = s.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ")) return false
        val decoded = try {
            String(Base64.decode(h.substring(6).trim(), Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        return decoded == "${Config.USERNAME}:${Config.PASSWORD}"
    }

    private fun resolve(uri: String): Target? {
        val parts = uri.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." || it.contains('\u0000') }) return null
        if (parts.isEmpty()) return Target.Root
        val vol = Volumes.list(ctx).firstOrNull { it.name == parts[0] } ?: return null
        val f = if (parts.size == 1) vol else File(vol, parts.drop(1).joinToString("/"))
        if (!f.exists()) return null
        val root = vol.canonicalPath
        val real = f.canonicalPath
        if (real != root && !real.startsWith(root + File.separator)) return null
        return Target.Node(f)
    }

    private fun children(t: Target): List<Entry> {
        val list = when (t) {
            is Target.Root -> Volumes.list(ctx).map { Entry(it.name, true, 0L, it.lastModified()) }
            is Target.Node -> (t.file.listFiles() ?: emptyArray<File>())
                .filter { !it.name.startsWith(".") }
                .map { Entry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified()) }
        }
        return list.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase(Locale.ROOT) }))
    }

    private fun enc(p: String) = p.split('/').joinToString("/") { Uri.encode(it) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
    }

    private fun httpDate(ms: Long): String = dateFmt.get()!!.format(Date(ms))

    private fun mimeOf(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "ts", "m2ts" -> "video/mp2t"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "srt", "txt" -> "text/plain; charset=utf-8"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    // ---------- HTML dizin listesi ----------

    private fun listing(path: String, t: Target): Response {
        val base = if (path.endsWith("/")) path else "$path/"
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Index of ${esc(base)}</title></head><body>")
        sb.append("<h1>Index of ${esc(base)}</h1><ul>")
        if (base != "/") {
            val parent = base.trimEnd('/').substringBeforeLast('/', "") + "/"
            sb.append("<li><a href=\"${enc(parent)}\">../</a></li>")
        }
        for (e in children(t)) {
            val href = enc(base + e.name) + if (e.dir) "/" else ""
            sb.append("<li><a href=\"$href\">${esc(e.name)}${if (e.dir) "/" else ""}</a></li>")
        }
        sb.append("</ul></body></html>")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", sb.toString())
    }

    // ---------- WebDAV PROPFIND ----------

    private fun prop(href: String, name: String, dir: Boolean, size: Long, modified: Long): String {
        val sb = StringBuilder()
        sb.append("<D:response><D:href>${esc(enc(href))}</D:href><D:propstat><D:prop>")
        sb.append("<D:displayname>${esc(name)}</D:displayname>")
        if (dir) {
            sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            sb.append("<D:resourcetype/><D:getcontentlength>$size</D:getcontentlength>")
            sb.append("<D:getcontenttype>${esc(mimeOf(name))}</D:getcontenttype>")
        }
        sb.append("<D:getlastmodified>${httpDate(modified)}</D:getlastmodified>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        return sb.toString()
    }

    private fun propfind(session: IHTTPSession, path: String, t: Target, isDir: Boolean): Response {
        // İstek gövdesini tüket (keep-alive bağlantıyı bozmasın)
        try { session.parseBody(HashMap()) } catch (_: Exception) {}

        val depth = session.headers["depth"] ?: "1"
        val base = if (path.endsWith("/")) path else "$path/"
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")

        when (t) {
            is Target.Root -> xml.append(prop("/", "", true, 0L, System.currentTimeMillis()))
            is Target.Node -> {
                val f = t.file
                xml.append(
                    if (isDir) prop(base, f.name, true, 0L, f.lastModified())
                    else prop(path, f.name, false, f.length(), f.lastModified())
                )
            }
        }
        if (isDir && depth != "0") {
            for (e in children(t)) {
                xml.append(prop(base + e.name + if (e.dir) "/" else "", e.name, e.dir, e.size, e.modified))
            }
        }
        xml.append("</D:multistatus>")
        return newFixedLengthResponse(MultiStatus, "application/xml; charset=utf-8", xml.toString())
    }

    // ---------- Dosya gönderme (Range destekli) ----------

    private fun sendFile(session: IHTTPSession, f: File): Response {
        val len = f.length()
        val mime = mimeOf(f.name)
        if (len == 0L) return newFixedLengthResponse(Response.Status.OK, mime, "")

        var start = 0L
        var end = len - 1
        var partial = false

        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.substring(6).split(",")[0].trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash)
                val b = spec.substring(dash + 1)
                try {
                    if (a.isEmpty()) {
                        val n = b.toLong()
                        start = maxOf(0L, len - n)
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                    }
                    partial = true
                } catch (_: NumberFormatException) {
                    start = 0L
                    end = len - 1
                }
            }
        }

        if (partial && (start >= len || start > end)) {
            val r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
            r.addHeader("Content-Range", "bytes */$len")
            return r
        }

        val ins = FileInputStream(f)
        try {
            if (start > 0) ins.channel.position(start)
        } catch (e: Exception) {
            ins.close()
            throw e
        }

        val r = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime, ins, end - start + 1
        )
        r.addHeader("Accept-Ranges", "bytes")
        if (partial) r.addHeader("Content-Range", "bytes $start-$end/$len")
        return r
    }
}
