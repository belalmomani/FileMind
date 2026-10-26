package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.filemind.smartmanager.core.ColorAnalyzer
import com.filemind.smartmanager.core.ColorProfile
import java.io.File

/** Reads a photo at very low resolution and summarizes its colors (approximate by design). */
object ColorWork {
    fun profileOf(cr: ContentResolver, path: String): ColorProfile? {
        val raw = ImageHasher.thumbnail(cr, Uri.parse(path), 48) ?: return null
        val side = maxOf(raw.width, raw.height)
        val bmp = if (side > 32) Bitmap.createScaledBitmap(raw, maxOf(1, raw.width * 32 / side), maxOf(1, raw.height * 32 / side), true) else raw
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return ColorAnalyzer.analyze(px)
    }
}

/** On-disk cache of color profiles: path -> (signature, profile). Signature = the photo's date, so an edited photo is analyzed again. Local only. */
object ColorCache {
    private const val FILE = "color_cache_v1.tsv"

    fun load(ctx: Context): MutableMap<String, Pair<String, ColorProfile>> {
        val m = HashMap<String, Pair<String, ColorProfile>>()
        runCatching {
            File(ctx.filesDir, FILE).takeIf { it.exists() }?.useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    if (p.size == 3) ColorProfile.decode(p[2])?.let { m[p[0]] = p[1] to it }
                }
            }
        }
        return m
    }

    fun save(ctx: Context, m: Map<String, Pair<String, ColorProfile>>) {
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in m) w.write(k.replace('\t', ' ').replace('\n', ' ') + "\t" + v.first + "\t" + v.second.encode() + "\n")
            }
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
    }
}
