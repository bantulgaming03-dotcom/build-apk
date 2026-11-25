package com.klipotomatis.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val store = SettingsStore(ctx)
    private val lock = Any()
    private var job: Job? = null

    var ui by mutableStateOf(UiState())
        private set

    var settings by mutableStateOf(store.load())
        private set

    init {
        // Perbarui yt-dlp otomatis paling sering sekali per 12 jam (YouTube sering berubah).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                awaitEngine()
                if (System.currentTimeMillis() - store.lastEngineUpdate() > 12L * 3600 * 1000) {
                    doEngineUpdate()
                }
            } catch (e: Throwable) {
                // tidak fatal
            }
        }
    }

    private fun update(block: UiState.() -> UiState) {
        synchronized(lock) { ui = ui.block() }
    }

    private fun log(msg: String) = update { copy(logs = (logs + msg).takeLast(60)) }

    private fun setStep(step: String, progress: Float = -1f) =
        update { copy(step = step, progress = progress) }

    // ---------- input ----------

    fun setLink(v: String) {
        update { copy(ytLink = v, pickedUri = if (v.isNotBlank()) null else pickedUri, pickedName = if (v.isNotBlank()) "" else pickedName) }
    }

    fun setPicked(uri: Uri, name: String) {
        update { copy(pickedUri = uri, pickedName = name, ytLink = "") }
    }

    fun clearPicked() {
        update { copy(pickedUri = null, pickedName = "") }
    }

    fun dismissError() {
        update { copy(error = null) }
    }

    fun saveSettings(s: AppSettings) {
        store.save(s)
        settings = s
    }

    // ---------- mesin yt-dlp ----------

    private suspend fun awaitEngine() {
        var waited = 0
        while (!App.engineReady) {
            val err = App.engineError
            if (err != null) throw IllegalStateException("Mesin yt-dlp gagal dimulai: $err")
            delay(500)
            waited++
            if (waited > 240) throw IllegalStateException("Mesin yt-dlp belum siap (timeout).")
        }
    }

    private fun doEngineUpdate() {
        update { copy(engineStatus = "Memperbarui yt-dlp…") }
        try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            store.markEngineUpdated()
            update { copy(engineStatus = "yt-dlp: ${st ?: "selesai"}") }
        } catch (e: Throwable) {
            update { copy(engineStatus = "Gagal memperbarui: ${e.message}") }
        }
    }

    fun updateEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                awaitEngine()
                doEngineUpdate()
            } catch (e: Throwable) {
                update { copy(engineStatus = "Gagal: ${e.message}") }
            }
        }
    }

    // ---------- pipeline ----------

    fun cancel() {
        job?.cancel()
        Downloader.cancel()
    }

    fun start() {
        if (ui.running) return
        val s = settings
        val link = ui.ytLink.trim()
        val picked = ui.pickedUri
        if (link.isEmpty() && picked == null) {
            update { copy(error = "Tempel link YouTube atau pilih file video dulu.") }
            return
        }
        if (s.geminiKey.isBlank()) {
            update { copy(error = "Isi Gemini API key dulu di Pengaturan (ikon gerigi di kanan atas).") }
            return
        }
        update {
            copy(running = true, error = null, clips = emptyList(), logs = emptyList(), step = "Menyiapkan…", progress = -1f)
        }
        job = viewModelScope.launch {
            try {
                runPipeline(link, picked, s)
            } catch (e: CancellationException) {
                update { copy(step = "Dibatalkan.") }
                throw e
            } catch (e: Throwable) {
                update { copy(error = e.message ?: e.toString(), step = "Gagal.") }
            } finally {
                update { copy(running = false, progress = -1f) }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    File(ctx.filesDir, "work").deleteRecursively()
                }
            }
        }
    }

    private suspend fun runPipeline(link: String, picked: Uri?, s: AppSettings) {
        val workRoot = File(ctx.filesDir, "work")
        withContext(Dispatchers.IO) {
            workRoot.deleteRecursively()
            workRoot.mkdirs()
        }
        val client = GeminiClient(s.geminiKey.trim(), s.model.trim().ifEmpty { "gemini-flash-latest" })
        val renderer = ClipRenderer(ctx)

        // 1) Sumber video
        val source: SourceVideo
        if (link.isNotEmpty()) {
            setStep("Menyiapkan mesin yt-dlp…")
            awaitEngine()
            setStep("Mengunduh video YouTube…")
            source = Downloader.download(ctx, link, s.high1080, File(workRoot, "dl")) { p, _ ->
                val pr = if (p in 0f..100f) p / 100f else -1f
                update { copy(step = "Mengunduh video YouTube… ${if (pr >= 0) (pr * 100).toInt().toString() + "%" else ""}", progress = pr) }
            }
            log("Video terunduh" + if (source.captionFile != null) " + subtitle ditemukan." else " (tanpa subtitle).")
        } else {
            source = SourceVideo(picked!!, null)
        }

        // 2) Durasi
        val durationSec = withContext(Dispatchers.IO) { probeDuration(source.uri) }
        if (durationSec <= 0.0) throw IllegalStateException("Tidak bisa membaca durasi video.")
        log("Durasi video: ${fmt(durationSec)}")

        // 3) Transkrip
        var segs: List<Seg> = emptyList()
        val cap = source.captionFile
        if (cap != null) {
            segs = withContext(Dispatchers.IO) { runCatching { Captions.parse(cap) }.getOrDefault(emptyList()) }
        }
        if (segs.size < 5) {
            log("Tidak ada subtitle, transkripsi audio dengan Gemini…")
            segs = transcribe(client, renderer, source.uri, File(workRoot, "audio"))
        } else {
            log("Memakai subtitle YouTube (${segs.size} baris).")
        }
        if (segs.isEmpty()) throw IllegalStateException("Transkrip kosong. Video mungkin tanpa suara/ucapan.")

        // 4) Analisis AI
        setStep("AI mencari bagian terpenting…")
        val plans = withContext(Dispatchers.IO) { Analyzer.pickClips(client, segs, durationSec, s) }
        log("AI memilih ${plans.size} klip.")

        // 5) Render
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        outDir.mkdirs()
        var ok = 0
        var lastErr: Throwable? = null
        plans.forEachIndexed { i, plan ->
            setStep("Merender klip ${i + 1}/${plans.size}…", i.toFloat() / plans.size)
            val tmp = File(outDir, "klip_${stamp}_${i + 1}.mp4")
            try {
                renderer.render(source.uri, plan, segs, tmp, s) { p ->
                    update { copy(progress = (i + p.coerceIn(0f, 1f)) / plans.size) }
                }
                val safe = plan.title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(30)
                val name = "Klip_${stamp}_${i + 1}_$safe.mp4"
                val uri = withContext(Dispatchers.IO) {
                    val u = MediaSaver.saveToGallery(ctx, tmp, name)
                    tmp.delete()
                    u
                }
                ok++
                val result = ClipResult(plan.title, plan.reason, plan.score, plan.start, plan.end, uri)
                update { copy(clips = clips + result) }
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (e: Throwable) {
                tmp.delete()
                lastErr = e
                log("Klip ${i + 1} gagal dirender: ${e.message}")
            }
        }
        if (ok == 0) {
            throw IllegalStateException("Semua klip gagal dirender. ${lastErr?.message ?: ""}")
        }
        setStep("Selesai: $ok klip tersimpan di Galeri (Movies/KlipOtomatis).", 1f)
    }

    private suspend fun transcribe(
        client: GeminiClient,
        renderer: ClipRenderer,
        uri: Uri,
        audioDir: File
    ): List<Seg> {
        setStep("Mengekstrak audio…")
        var chunks = withContext(Dispatchers.IO) { AudioExtractor.extractAdtsChunks(ctx, uri, audioDir) }
        if (chunks == null) {
            setStep("Mengonversi audio ke AAC…")
            audioDir.mkdirs()
            val m4a = File(audioDir, "audio_convert.m4a")
            renderer.exportAudioOnly(uri, m4a)
            chunks = withContext(Dispatchers.IO) {
                AudioExtractor.extractAdtsChunks(ctx, Uri.fromFile(m4a), File(audioDir, "conv"))
            }
        }
        val ready = chunks ?: throw IllegalStateException("Format audio video ini tidak didukung.")
        val all = ArrayList<Seg>()
        ready.forEachIndexed { i, ch ->
            setStep("Transkripsi audio ${i + 1}/${ready.size}…", i.toFloat() / ready.size)
            val part = withContext(Dispatchers.IO) { Analyzer.transcribeChunk(client, ch) }
            all.addAll(part)
        }
        return all.sortedBy { it.start }
    }

    private fun probeDuration(uri: Uri): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } finally {
            r.release()
        }
    }

    companion object {
        fun fmt(sec: Double): String {
            val t = sec.toInt()
            return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
        }
    }
}
