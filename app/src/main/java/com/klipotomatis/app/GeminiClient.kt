package com.klipotomatis.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Klien REST sederhana untuk Gemini API (generateContent). */
class GeminiClient(private val apiKey: String, private val model: String) {

    fun generate(parts: JSONArray, json: Boolean = true, temperature: Double = 0.3): String {
        val genCfg = JSONObject().put("temperature", temperature)
        if (json) genCfg.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
            )
            .put("generationConfig", genCfg)
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")

        var lastErr = ""
        for (attempt in 0 until 4) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 30_000
                conn.readTimeout = 240_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) return parseText(text)
                if (code == 429 || code >= 500) {
                    lastErr = "HTTP $code ${errorMessage(text)}"
                    Thread.sleep(2000L * (attempt + 1))
                } else {
                    throw IOException("Gemini HTTP $code: ${errorMessage(text)}")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Gemini gagal setelah beberapa percobaan: $lastErr")
    }

    private fun errorMessage(text: String): String {
        return try {
            JSONObject(text).optJSONObject("error")?.optString("message") ?: text.take(300)
        } catch (e: Exception) {
            text.take(300)
        }
    }

    private fun parseText(text: String): String {
        val root = JSONObject(text)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            }
        }
        var out = sb.toString().trim()
        if (out.isEmpty()) {
            val reason = root.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason", "")
            throw IOException("Gemini tidak mengembalikan teks (finishReason=$reason).")
        }
        if (out.startsWith("```")) {
            out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        return out
    }
}
