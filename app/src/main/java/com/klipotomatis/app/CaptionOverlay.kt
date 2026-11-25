package com.klipotomatis.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

/** Satu baris subtitle dengan waktu relatif terhadap awal klip (mikrodetik). */
class Cue(val startUs: Long, val endUs: Long, val text: String)

/**
 * Overlay satu bitmap penuh-layar (ukuran sama dengan frame output) yang berisi
 * judul hook (3,5 detik pertama) dan subtitle yang berganti sesuai waktu.
 */
class CaptionOverlay(
    private val w: Int,
    private val h: Int,
    private val cues: List<Cue>,
    private val title: String?
) : BitmapOverlay() {

    private val settings: OverlaySettings = OverlaySettings.Builder().build()
    private val empty: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap = empty

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val showTitle = !title.isNullOrBlank() && presentationTimeUs < 3_500_000L
        var cue: Cue? = null
        for (c in cues) {
            if (presentationTimeUs >= c.startUs && presentationTimeUs < c.endUs) {
                cue = c
                break
            }
        }
        val key = "${cue?.startUs ?: -1L}|$showTitle"
        if (key == cachedKey) return cachedBitmap
        cachedKey = key
        cachedBitmap = if (cue == null && !showTitle) {
            empty
        } else {
            draw(cue?.text, if (showTitle) title else null)
        }
        return cachedBitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun draw(caption: String?, titleText: String?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (titleText != null) drawTitle(c, titleText)
        if (caption != null) drawCaption(c, caption)
        return bmp
    }

    private fun layoutOf(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

    private fun drawCaption(c: Canvas, text: String) {
        val maxW = (w * 0.86f).toInt()
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fill.textSize = w * 0.075f
        fill.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fill.color = Color.WHITE
        val outline = TextPaint(fill)
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = w * 0.014f
        outline.strokeJoin = Paint.Join.ROUND
        outline.color = Color.BLACK

        val lo = layoutOf(text, outline, maxW)
        val lf = layoutOf(text, fill, maxW)
        val x = (w - maxW) / 2f
        val y = h * 0.68f - lf.height / 2f
        c.save()
        c.translate(x, y)
        lo.draw(c)
        lf.draw(c)
        c.restore()
    }

    private fun drawTitle(c: Canvas, text: String) {
        val pad = w * 0.04f
        val maxW = (w * 0.80f).toInt()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = w * 0.058f
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.color = Color.WHITE
        val layout = layoutOf(text, tp, maxW)
        var lineMax = 0f
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > lineMax) lineMax = layout.getLineWidth(i)
        }
        val boxW = lineMax + 2 * pad
        val boxH = layout.height + 2 * pad
        val left = (w - boxW) / 2f
        val top = h * 0.09f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = Color.argb(190, 20, 20, 30)
        c.drawRoundRect(RectF(left, top, left + boxW, top + boxH), pad, pad, bg)
        c.save()
        c.translate(left + pad - (layout.width - lineMax) / 2f, top + pad)
        layout.draw(c)
        c.restore()
    }
}
