package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.filemind.smartmanager.core.GeoResult
import java.io.File

/**
 * Reads the GPS location stored INSIDE a photo (EXIF) on this device. Nothing is sent anywhere.
 * On Android 10+ MediaStore photos hide their location unless the user granted "media location access"; without it the read throws and we return null
 * (= unreadable, NOT "no location").
 */
object GeoReader {
    fun read(cr: ContentResolver, path: String): GeoResult? = try {
        var uri = Uri.parse(path)
        if (uri.authority == "media" && Build.VERSION.SDK_INT >= 29) uri = MediaStore.setRequireOriginal(uri)
        cr.openInputStream(uri)?.use { ins ->
            val out = FloatArray(2)
            if (ExifInterface(ins).getLatLong(out)) GeoResult(true, out[0].toDouble(), out[1].toDouble()) else GeoResult(false, 0.0, 0.0)
        }
    } catch (e: Exception) { null }
}

/** Private on-device cache of the locations found: path -> (signature, result). Deleted when the user turns the map off. */
object GeoCache {
    private const val FILE = "geo_cache_v1.tsv"

    fun load(ctx: Context): MutableMap<String, Pair<String, GeoResult>> {
        val m = HashMap<String, Pair<String, GeoResult>>()
        runCatching {
            File(ctx.filesDir, FILE).takeIf { it.exists() }?.useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    if (p.size == 3) GeoResult.decode(p[2])?.let { m[p[0]] = p[1] to it }
                }
            }
        }
        return m
    }

    fun save(ctx: Context, m: Map<String, Pair<String, GeoResult>>) {
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in m) w.write(k.replace('\t', ' ').replace('\n', ' ') + "\t" + v.first + "\t" + v.second.encode() + "\n")
            }
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
        runCatching { File(ctx.filesDir, "$FILE.tmp").delete() }
    }
}
