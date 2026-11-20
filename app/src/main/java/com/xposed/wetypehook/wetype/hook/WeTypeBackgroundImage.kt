package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.io.File

/**
 * Decodes the per-mode background image on demand. Only one image is live per mode, so a
 * single keyed slot (path + mtime + size + decode width) covers both profiles and re-picks
 * without holding every bitmap the user has ever selected.
 */
internal object WeTypeBackgroundImage {
    private const val MAX_DECODE_WIDTH = 2048

    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap? = null

    fun decode(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.isFile) return null
        val key = "$path\n${file.lastModified()}\n${file.length()}"
        if (key == cachedKey) return cachedBitmap

        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_DECODE_WIDTH) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        // Cache failures too, so a broken file does not re-decode on every frame.
        cachedBitmap = decoded
        cachedKey = key
        return decoded
    }
}

/**
 * Draws the bitmap centered and scaled to cover the drawable bounds (CSS object-fit: cover).
 * The background carrier already clips to its continuous-corner outline, so the image can
 * stay full-bleed.
 */
internal class WeTypeBackgroundImageDrawable(
    private val bitmap: Bitmap,
    // 50 keeps the image untouched; lower overlays black, higher overlays white.
    private val brightness: Int = 50
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val destination = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || bitmap.isRecycled) return
        val widthScale = bounds.width().toFloat() / bitmap.width
        val heightScale = bounds.height().toFloat() / bitmap.height
        val scale = maxOf(widthScale, heightScale)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        destination.set(
            bounds.left - (drawWidth - bounds.width()) / 2f,
            bounds.top - (drawHeight - bounds.height()) / 2f,
            bounds.right + (drawWidth - bounds.width()) / 2f,
            bounds.bottom + (drawHeight - bounds.height()) / 2f
        )
        val clip = Rect(bounds)
        canvas.save()
        canvas.clipRect(clip)
        canvas.drawBitmap(bitmap, null, destination, paint)
        if (brightness != 50) {
            scrimPaint.color = if (brightness < 50) {
                val strength = (50 - brightness) / 50f
                Color.argb((strength * 179).toInt(), 0, 0, 0)
            } else {
                val strength = (brightness - 50) / 50f
                Color.argb((strength * 128).toInt(), 255, 255, 255)
            }
            canvas.drawRect(clip, scrimPaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
