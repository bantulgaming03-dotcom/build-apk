package com.klipotomatis.app

import org.json.JSONObject
import java.io.File

/** Parser subtitle YouTube (json3 dan vtt) menjadi daftar Seg. */
object Captions {

    fun parse(file: File): List<Seg> {
        val text = file.readText()
        val raw = if (file.name.endsWith(".json3")) parseJson3(text) else parseVtt(text)
        return clean(raw)
    }

    private fun parseJson3(text: String): List<Seg> {
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Seg>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) {
                sb.append(segs.optJSONObject(j)?.optString("utf8", "") ?: "")
            }
            val t = sb.toString().replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) continue
            val start = ev.optLong("tStartMs", 0L) / 1000.0
            val dur = ev.optLong("dDurationMs", 0L) / 1000.0
            out.add(Seg(start, start + (if (dur > 0) dur else 2.0), t))
        }
        return out
    }

    private fun parseVtt(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                val parts = line.split("-->")
                val s = ts(parts[0].trim())
                val e = ts(parts[1].trim().substringBefore(" "))
                val sb = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    sb.append(lines[i].replace(Regex("<[^>]+>"), "")).append(' ')
                    i++
                }
                val t = sb.toString().replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty()) out.add(Seg(s, e, t))
            } else {
                i++
            }
        }
        return out
    }

    private fun ts(s: String): Double {
        return try {
            val p = s.replace(',', '.').split(":")
            when (p.size) {
                3 -> p[0].toDouble() * 3600 + p[1].toDouble() * 60 + p[2].toDouble()
                2 -> p[0].toDouble() * 60 + p[1].toDouble()
                else -> 0.0
            }
        } catch (e: NumberFormatException) {
            0.0
        }
    }

    /** Buang duplikat (caption bergulir YouTube) dan rapikan overlap waktu. */
    private fun clean(raw: List<Seg>): List<Seg> {
        val sorted = raw.sortedBy { it.start }
        val dedup = ArrayList<Seg>()
        for (sg in sorted) {
            val prev = dedup.lastOrNull()
            if (prev == null) {
                dedup.add(sg)
                continue
            }
            if (sg.text == prev.text) continue
            if (sg.text.startsWith(prev.text)) {
                val rest = sg.text.removePrefix(prev.text).trim()
                if (rest.isNotEmpty()) dedup.add(Seg(sg.start, sg.end, rest))
                continue
            }
            dedup.add(sg)
        }
        val out = ArrayList<Seg>()
        for (i in dedup.indices) {
            val cur = dedup[i]
            var end = cur.end
            if (i + 1 < dedup.size) {
                val next = dedup[i + 1].start
                if (next > cur.start && next < end) end = next
            }
            if (end - cur.start < 0.3) end = cur.start + 0.3
            out.add(Seg(cur.start, end, cur.text))
        }
        return out
    }
}
