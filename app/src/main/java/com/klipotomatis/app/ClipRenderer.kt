package com.klipotomatis.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/** Merender klip vertikal 9:16 (crop, subtitle, judul) memakai Media3 Transformer. */
class ClipRenderer(private val context: Context) {

    suspend fun render(
        source: Uri,
        plan: ClipPlan,
        segs: List<Seg>,
        out: File,
        s: AppSettings,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.Main) {
        val w = if (s.high1080) 1080 else 720
        val h = if (s.high1080) 1920 else 1280
        val startMs = (plan.start * 1000).toLong()
        val endMs = (plan.end * 1000).toLong()

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(clipping)
            .build()

        val effects = ArrayList<Effect>()
        val layout = if (s.cropFill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        effects.add(Presentation.createForWidthAndHeight(w, h, layout))
        if (s.captionsOn || s.titleOn) {
            val cues = if (s.captionsOn) buildCues(plan, segs) else emptyList()
            val title = if (s.titleOn) plan.title else null
            val overlay = CaptionOverlay(w, h, cues, title)
            effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
        }
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(listOf<AudioProcessor>(), effects))
            .build()
        runExport(edited, out, if (s.high1080) 9_000_000 else 5_000_000, onProgress)
    }

    /** Ekspor audio saja (AAC di dalam m4a) untuk video yang audionya bukan AAC. */
    suspend fun exportAudioOnly(source: Uri, out: File) = withContext(Dispatchers.Main) {
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setRemoveVideo(true)
            .build()
        runExport(edited, out, 2_000_000) { }
    }

    private suspend fun runExport(
        edited: EditedMediaItem,
        out: File,
        videoBitrate: Int,
        onProgress: (Float) -> Unit
    ) {
        if (out.exists()) out.delete()
        suspendCancellableCoroutine<Unit> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
                )
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(listener)
                .build()
            cont.invokeOnCancellation {
                handler.post {
                    try {
                        transformer.cancel()
                    } catch (e: Throwable) {
                        // abaikan
                    }
                }
            }
            try {
                transformer.start(edited, out.absolutePath)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            val holder = ProgressHolder()
            val poller = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    handler.postDelayed(this, 400)
                }
            }
            handler.postDelayed(poller, 400)
        }
    }

    /** Pecah transkrip yang masuk rentang klip menjadi subtitle pendek (maks. 5 kata). */
    private fun buildCues(plan: ClipPlan, segs: List<Seg>): List<Cue> {
        val out = ArrayList<Cue>()
        for (seg in segs) {
            if (seg.end <= plan.start || seg.start >= plan.end) continue
            val s = max(seg.start, plan.start)
            val e = min(seg.end, plan.end)
            if (e <= s) continue
            val words = seg.text.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            val groups = ArrayList<String>()
            var cur = StringBuilder()
            var count = 0
            for (wd in words) {
                if (count >= 5 || (count > 0 && cur.length + wd.length + 1 > 32)) {
                    groups.add(cur.toString())
                    cur = StringBuilder()
                    count = 0
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(wd)
                count++
            }
            if (cur.isNotEmpty()) groups.add(cur.toString())
            val totalChars = groups.sumOf { it.length }.toDouble()
            var t = s
            for (g in groups) {
                val d = (e - s) * g.length / totalChars
                val a = ((t - plan.start) * 1_000_000).toLong()
                val b = ((t + d - plan.start) * 1_000_000).toLong()
                out.add(Cue(a, b, g))
                t += d
            }
        }
        return out
    }
}
