package com.klipotomatis.app

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Mengunduh video YouTube (dan subtitle bila ada) lewat yt-dlp yang tertanam di APK. */
object Downloader {
    const val PROCESS_ID = "klip_download"

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov")

    suspend fun download(
        context: Context,
        url: String,
        high1080: Boolean,
        workDir: File,
        onProgress: (Float, String) -> Unit
    ): SourceVideo = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val h = if (high1080) 1080 else 720
        val format = "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/" +
            "b[height<=$h][vcodec^=avc1][ext=mp4]/" +
            "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/" +
            "b[ext=mp4]/b"

        // Percobaan 1: bawaan yt-dlp. Percobaan 2: client android_vr (tidak butuh JS runtime).
        val attempts = listOf(
            emptyList<String>(),
            listOf("--extractor-args", "youtube:player_client=android_vr")
        )
        var lastError: Throwable? = null
        for (extra in attempts) {
            try {
                workDir.listFiles()?.forEach { it.delete() }
                val req = YoutubeDLRequest(url)
                req.addOption("-f", format)
                req.addOption("--merge-output-format", "mp4")
                req.addOption("--no-playlist")
                req.addOption("--no-mtime")
                req.addOption("--write-subs")
                req.addOption("--sub-langs", "id.*,en.*")
                req.addOption("--sub-format", "json3/vtt")
                req.addOption("--socket-timeout", "30")
                req.addOption("--retries", "5")
                req.addOption("-o", File(workDir, "video.%(ext)s").absolutePath)
                if (extra.size == 2) req.addOption(extra[0], extra[1])
                YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, _, line ->
                    onProgress(progress, line)
                }
                lastError = null
                break
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = e
            }
        }
        lastError?.let { throw IllegalStateException(describe(it), it) }

        val video = workDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("File video hasil unduhan tidak ditemukan.")

        var caption = pickCaption(workDir)
        if (caption == null) {
            // Tidak ada subtitle manual: coba subtitle otomatis bahasa asli (xx-orig). Tidak fatal bila gagal.
            try {
                val req2 = YoutubeDLRequest(url)
                req2.addOption("--skip-download")
                req2.addOption("--no-playlist")
                req2.addOption("--write-auto-subs")
                req2.addOption("--sub-langs", ".*-orig")
                req2.addOption("--sub-format", "json3/vtt")
                req2.addOption("-o", File(workDir, "video.%(ext)s").absolutePath)
                YoutubeDL.getInstance().execute(req2, PROCESS_ID) { _, _, _ -> }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
            }
            caption = pickCaption(workDir)
        }
        SourceVideo(Uri.fromFile(video), caption)
    }

    private fun lang(f: File): String =
        f.name.removePrefix("video.").substringBeforeLast(".")

    private fun pickCaption(dir: File): File? {
        val subs = dir.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".json3") || it.name.endsWith(".vtt"))
        } ?: return null
        if (subs.isEmpty()) return null
        val manual = subs.filter { !lang(it).endsWith("-orig") }
        val pool = if (manual.isNotEmpty()) manual else subs
        return pool.firstOrNull { lang(it).startsWith("id") }
            ?: pool.firstOrNull { lang(it).startsWith("en") }
            ?: pool.first()
    }

    private fun describe(e: Throwable): String {
        val msg = (e.message ?: e.toString()).lines().lastOrNull { it.isNotBlank() } ?: e.toString()
        return "Gagal mengunduh video: $msg\n" +
            "Tips: coba tombol \"Perbarui mesin\" di Pengaturan, atau unduh videonya lewat cara lain lalu pakai tombol \"Pilih file video\"."
    }

    fun cancel() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (e: Throwable) {
            // abaikan
        }
    }
}
