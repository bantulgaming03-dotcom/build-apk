package com.klipotomatis.app

import android.net.Uri
import java.io.File

/** Satu potongan transkrip (detik). */
data class Seg(val start: Double, val end: Double, val text: String)

/** Rencana klip hasil analisis AI. */
data class ClipPlan(
    val start: Double,
    val end: Double,
    val title: String,
    val reason: String,
    val score: Int
)

/** Klip yang sudah dirender dan tersimpan di galeri. */
data class ClipResult(
    val title: String,
    val reason: String,
    val score: Int,
    val start: Double,
    val end: Double,
    val uri: Uri
)

data class SourceVideo(
    val uri: Uri,
    val captionFile: File?
)

data class AppSettings(
    val geminiKey: String = "",
    val model: String = "gemini-flash-latest",
    val clipCount: Int = 5,
    val minSec: Int = 30,
    val maxSec: Int = 60,
    val high1080: Boolean = false,
    val cropFill: Boolean = true,
    val captionsOn: Boolean = true,
    val titleOn: Boolean = true
)

data class UiState(
    val ytLink: String = "",
    val pickedUri: Uri? = null,
    val pickedName: String = "",
    val running: Boolean = false,
    val step: String = "",
    /** 0..1, atau -1 jika tidak diketahui (indeterminate). */
    val progress: Float = -1f,
    val logs: List<String> = emptyList(),
    val clips: List<ClipResult> = emptyList(),
    val error: String? = null,
    val engineStatus: String = ""
)
