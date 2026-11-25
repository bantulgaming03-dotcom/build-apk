package com.klipotomatis.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Transkripsi audio (bila tak ada subtitle) dan pemilihan bagian terbaik memakai Gemini. */
object Analyzer {

    private const val TRANSCRIBE_PROMPT =
        "Transcribe this audio verbatim in its original language. Do not translate. " +
            "Return ONLY JSON: {\"segments\":[{\"start\":number,\"end\":number,\"text\":string}]}. " +
            "Each segment is one sentence or phrase of roughly 2 to 8 seconds. " +
            "start and end are seconds (one decimal) counted from the very beginning of THIS audio. " +
            "Skip parts with only silence or music. No commentary."

    fun transcribeChunk(client: GeminiClient, chunk: AudioExtractor.Chunk): List<Seg> {
        val bytes = chunk.file.readBytes()
        val parts = JSONArray()
            .put(JSONObject().put("text", TRANSCRIBE_PROMPT))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "audio/aac")
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                )
            )
        val txt = client.generate(parts, json = true, temperature = 0.0).trim()
        val arr: JSONArray = if (txt.startsWith("[")) {
            JSONArray(txt)
        } else {
            JSONObject(txt).optJSONArray("segments") ?: JSONArray()
        }
        val out = ArrayList<Seg>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = timeOf(o, "start")
            var e = timeOf(o, "end")
            val t = o.optString("text", "").trim()
            if (s.isNaN() || t.isEmpty()) continue
            if (e.isNaN() || e <= s) e = s + 3.0
            out.add(Seg(chunk.startSec + s, chunk.startSec + e, t))
        }
        return out
    }

    private fun timeOf(o: JSONObject, key: String): Double {
        val v = o.opt(key) ?: return Double.NaN
        return when (v) {
            is Number -> v.toDouble()
            is String -> parseTs(v)
            else -> Double.NaN
        }
    }

    private fun parseTs(s: String): Double {
        val t = s.trim()
        val plain = t.toDoubleOrNull()
        if (plain != null) return plain
        val p = t.split(":")
        return try {
            when (p.size) {
                2 -> p[0].toDouble() * 60 + p[1].toDouble()
                3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
                else -> Double.NaN
            }
        } catch (e: NumberFormatException) {
            Double.NaN
        }
    }

    private fun condense(segs: List<Seg>): String {
        val sb = StringBuilder()
        val cur = StringBuilder()
        var curStart = -1.0
        var curEnd = 0.0
        fun flush() {
            if (cur.isNotEmpty()) {
                sb.append('[').append(curStart.toInt()).append('-').append(curEnd.toInt()).append("] ")
                    .append(cur.toString().trim()).append('\n')
                cur.clear()
            }
            curStart = -1.0
        }
        for (sg in segs) {
            if (curStart < 0) curStart = sg.start
            cur.append(sg.text).append(' ')
            curEnd = sg.end
            if (curEnd - curStart >= 12.0) flush()
        }
        flush()
        return sb.toString()
    }

    fun pickClips(
        client: GeminiClient,
        segs: List<Seg>,
        durationSec: Double,
        s: AppSettings
    ): List<ClipPlan> {
        if (segs.isEmpty()) throw IllegalStateException("Transkrip kosong, tidak ada yang bisa dianalisis.")
        val total = if (durationSec > 0) durationSec else segs.last().end
        val want = s.clipCount + 2
        val prompt = "You are an expert short-form video editor (TikTok / Reels / YouTube Shorts).\n" +
            "Below is a timestamped transcript of a video that is ${total.toInt()} seconds long. " +
            "Each line is: [startSeconds-endSeconds] text.\n\n" +
            "Pick the $want best self-contained moments to cut into vertical short clips.\n" +
            "Rules:\n" +
            "- Each clip must last between ${s.minSec} and ${s.maxSec} seconds.\n" +
            "- Start at the beginning of a sentence with a strong hook and end where a thought is complete. " +
            "Use only times that appear in the transcript lines.\n" +
            "- Clips must not overlap.\n" +
            "- Prefer surprising claims, emotional peaks, concrete stories, practical tips, strong opinions, funny moments. " +
            "Avoid intros, outros, sponsor reads, small talk and filler.\n" +
            "- title: a catchy hook title of at most 8 words, in the same language as the transcript.\n" +
            "- reason: one short sentence in Indonesian explaining why this moment is strong.\n" +
            "- score: integer 1-100 for viral potential.\n" +
            "Return ONLY JSON: {\"clips\":[{\"start\":number,\"end\":number,\"title\":string,\"reason\":string,\"score\":number}]}\n\n" +
            "TRANSCRIPT:\n" + condense(segs)

        val txt = client.generate(JSONArray().put(JSONObject().put("text", prompt)), json = true, temperature = 0.3).trim()
        val arr: JSONArray = if (txt.startsWith("[")) {
            JSONArray(txt)
        } else {
            JSONObject(txt).optJSONArray("clips") ?: JSONArray()
        }

        class Raw(val start: Double, val end: Double, val title: String, val reason: String, val score: Int)
        val raws = ArrayList<Raw>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val st = timeOf(o, "start")
            val en = timeOf(o, "end")
            if (st.isNaN() || en.isNaN() || en <= st) continue
            raws.add(
                Raw(
                    st, en,
                    o.optString("title", "").trim(),
                    o.optString("reason", "").trim(),
                    o.optInt("score", 50)
                )
            )
        }
        if (raws.isEmpty()) throw IllegalStateException("AI tidak menemukan bagian yang cocok. Coba video lain atau ubah durasi klip.")

        val starts = segs.map { it.start }
        val ends = segs.map { it.end }
        fun nearest(list: List<Double>, v: Double): Double = list.minByOrNull { abs(it - v) } ?: v

        val minD = s.minSec.toDouble()
        val maxD = s.maxSec.toDouble()
        val chosen = ArrayList<ClipPlan>()
        for (r in raws.sortedByDescending { it.score }) {
            val st = nearest(starts, r.start)
            var en = nearest(ends, r.end)
            if (en <= st) continue
            if (en - st > maxD + 3) {
                en = segs.filter { it.end >= st + minD && it.end <= st + maxD }.maxOfOrNull { it.end } ?: (st + maxD)
            } else if (en - st < minD - 3) {
                en = segs.filter { it.end >= st + minD && it.end <= st + maxD + 3 }.minOfOrNull { it.end } ?: (st + minD)
            }
            en = min(en, total)
            if (en - st < 5.0) continue
            if (chosen.any { st < it.end && en > it.start }) continue
            chosen.add(
                ClipPlan(
                    start = max(0.0, st - 0.1),
                    end = min(total, en + 0.25),
                    title = r.title.ifEmpty { "Momen terbaik" }.take(80),
                    reason = r.reason,
                    score = r.score.coerceIn(1, 100)
                )
            )
            if (chosen.size >= s.clipCount) break
        }
        if (chosen.isEmpty()) throw IllegalStateException("Tidak ada klip valid setelah disesuaikan dengan transkrip.")
        return chosen
    }
}
