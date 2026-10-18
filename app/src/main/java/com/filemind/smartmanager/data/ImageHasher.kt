package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.filemind.smartmanager.core.PerceptualHash

object ImageHasher {
    /** Decodes a downsampled copy (never the full image). Returns null for undecodable files (e.g. some HEIC). */
    fun thumbnail(cr: ContentResolver, uri: Uri, target: Int = 256): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > target * 2 && bounds.outHeight / sample > target * 2) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (e: Exception) { null }
    }

    fun hash(cr: ContentResolver, uri: Uri): Long? {
        val bmp = thumbnail(cr, uri) ?: return null
        val small = Bitmap.createScaledBitmap(bmp, PerceptualHash.W, PerceptualHash.H, true)
        val g = IntArray(PerceptualHash.W * PerceptualHash.H) { i ->
            val c = small.getPixel(i % PerceptualHash.W, i / PerceptualHash.W)
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        if (small !== bmp) small.recycle(); bmp.recycle()
        return PerceptualHash.dHash(g)
    }
}
