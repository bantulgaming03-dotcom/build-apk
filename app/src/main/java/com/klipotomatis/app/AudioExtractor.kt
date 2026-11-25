package com.klipotomatis.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Mengambil audio AAC dari video tanpa re-encode, lalu membungkusnya sebagai ADTS (audio/aac)
 * dalam potongan kecil agar bisa dikirim ke Gemini.
 */
object AudioExtractor {

    class Chunk(val file: File, val startSec: Double)

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    /** Mengembalikan null bila audio bukan AAC-LC (pemanggil harus mengonversi dulu). */
    fun extractAdtsChunks(
        context: Context,
        uri: Uri,
        outDir: File,
        maxChunkBytes: Int = 6_000_000
    ): List<Chunk>? {
        outDir.mkdirs()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) {
                throw IllegalStateException("Video ini tidak punya jalur audio.")
            }
            if (fmt.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return null

            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var profile = 2
            val csd = fmt.getByteBuffer("csd-0")
            if (csd != null) {
                val dup = csd.duplicate()
                val d = ByteArray(dup.remaining())
                dup.get(d)
                if (d.isNotEmpty()) profile = (d[0].toInt() and 0xF8) shr 3
            }
            if (profile != 2) return null
            val freqIdx = SAMPLE_RATES.indexOf(sampleRate)
            if (freqIdx < 0 || channels < 1 || channels > 6) return null

            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(1 shl 20)
            val chunks = ArrayList<Chunk>()
            var out: FileOutputStream? = null
            var current: File? = null
            var chunkStartUs = 0L
            var bytes = 0
            var idx = 0

            fun closeChunk() {
                out?.close()
                out = null
                val f = current
                if (f != null && bytes > 0) chunks.add(Chunk(f, chunkStartUs / 1_000_000.0))
                current = null
                bytes = 0
            }

            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                if (out == null) {
                    val f = File(outDir, "audio_${idx++}.aac")
                    current = f
                    out = FileOutputStream(f)
                    chunkStartUs = if (t < 0) 0L else t
                    bytes = 0
                }
                val data = ByteArray(n)
                buf.position(0)
                buf.get(data, 0, n)
                val frameLen = n + 7
                val header = ByteArray(7)
                header[0] = 0xFF.toByte()
                header[1] = 0xF1.toByte()
                header[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
                header[3] = (((channels and 3) shl 6) + (frameLen shr 11)).toByte()
                header[4] = ((frameLen and 0x7FF) shr 3).toByte()
                header[5] = (((frameLen and 7) shl 5) + 0x1F).toByte()
                header[6] = 0xFC.toByte()
                out!!.write(header)
                out!!.write(data)
                bytes += frameLen
                ex.advance()
                if (bytes >= maxChunkBytes) closeChunk()
            }
            closeChunk()
            if (chunks.isEmpty()) throw IllegalStateException("Audio kosong.")
            return chunks
        } finally {
            ex.release()
        }
    }
}
