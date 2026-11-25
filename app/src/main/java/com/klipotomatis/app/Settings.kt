package com.klipotomatis.app

import android.content.Context

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("klip_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        geminiKey = sp.getString("gemini_key", "") ?: "",
        model = sp.getString("model", "gemini-flash-latest") ?: "gemini-flash-latest",
        clipCount = sp.getInt("clip_count", 5),
        minSec = sp.getInt("min_sec", 30),
        maxSec = sp.getInt("max_sec", 60),
        high1080 = sp.getBoolean("high_1080", false),
        cropFill = sp.getBoolean("crop_fill", true),
        captionsOn = sp.getBoolean("captions_on", true),
        titleOn = sp.getBoolean("title_on", true)
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("gemini_key", s.geminiKey)
            .putString("model", s.model)
            .putInt("clip_count", s.clipCount)
            .putInt("min_sec", s.minSec)
            .putInt("max_sec", s.maxSec)
            .putBoolean("high_1080", s.high1080)
            .putBoolean("crop_fill", s.cropFill)
            .putBoolean("captions_on", s.captionsOn)
            .putBoolean("title_on", s.titleOn)
            .apply()
    }

    fun lastEngineUpdate(): Long = sp.getLong("engine_update_at", 0L)

    fun markEngineUpdated() {
        sp.edit().putLong("engine_update_at", System.currentTimeMillis()).apply()
    }
}
