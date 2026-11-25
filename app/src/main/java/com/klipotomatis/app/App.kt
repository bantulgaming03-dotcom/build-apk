package com.klipotomatis.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Mesin tersembunyi (Python + yt-dlp + FFmpeg) diekstrak di background saat pertama kali jalan.
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                engineReady = true
            } catch (e: Throwable) {
                engineError = e.message ?: e.toString()
            }
        }.start()
    }

    companion object {
        @Volatile
        var engineReady: Boolean = false

        @Volatile
        var engineError: String? = null
    }
}
