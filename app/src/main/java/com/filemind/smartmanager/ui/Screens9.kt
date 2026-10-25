package com.filemind.smartmanager.ui

import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.Wall
import com.filemind.smartmanager.core.WallLayout
import com.filemind.smartmanager.data.ImageHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Small in-memory thumbnail cache + a limit on parallel decodes, so panning a big wall never decodes everything at once. */
private object WallThumbs {
    val cache = LruCache<String, ImageBitmap>(220)
    val permits = Semaphore(4)

    fun load(cr: android.content.ContentResolver, path: String): ImageBitmap? {
        val raw = ImageHasher.thumbnail(cr, Uri.parse(path), 120) ?: return null
        val side = maxOf(raw.width, raw.height)
        val bmp = if (side > 192) android.graphics.Bitmap.createScaledBitmap(raw, raw.width * 192 / side, raw.height * 192 / side, true) else raw
        return bmp.asImageBitmap().also { cache.put(path, it) }
    }
}

@Composable
private fun WallThumb(path: String, modifier: Modifier) {
    val cr = LocalContext.current.contentResolver
    val bmp by produceState<ImageBitmap?>(WallThumbs.cache.get(path), path) {
        if (value == null) value = withContext(Dispatchers.IO) { WallThumbs.permits.withPermit { WallThumbs.load(cr, path) } }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop) else Box(modifier.background(Color.LightGray))
}

/** Photo wall: a zoomable, pannable world. Pinch to zoom, drag to move, tap a photo to open it. Every folder is an island. */
@Composable
fun PhotoWallScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val req = remember { vm.wallRequest }
    val wall by produceState<Wall?>(null, req) {
        value = req?.let { r -> withContext(Dispatchers.Default) { WallLayout.build(r.photos, System.currentTimeMillis()) } }
    }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var minScale by remember { mutableStateOf(0.05f) }
    val w = wall

    fun fit() {
        val ww = w ?: return
        if (view.width == 0 || ww.width <= 0f) return
        val s = (view.width / ww.width).coerceIn(0.05f, 1f)
        scale = s; minScale = s * 0.5f; offset = Offset.Zero
    }
    LaunchedEffect(w, view) { fit() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }
            Text("${req?.title ?: ""} · ${req?.photos?.size ?: 0} صورة", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            TextButton({ fit() }) { Text("ملاءمة") }
        }
        Text("اسحب للتحرك، وبإصبعين للتكبير والتصغير، واضغط على صورة لفتحها. كل مجلد جزيرة، والأحدث في المقدمة.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { view = it }
                .pointerInput(w) {
                    detectTapGestures { pos ->
                        val ww = w ?: return@detectTapGestures
                        val wx = (pos.x - offset.x) / scale; val wy = (pos.y - offset.y) / scale
                        ww.tiles.lastOrNull { wx >= it.x && wx <= it.x + it.size && wy >= it.y && wy <= it.y + it.size }?.let { t ->
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.path), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { vm.message.value = "لا يوجد تطبيق لفتح هذه الصورة" }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(minScale, 4f)
                        val f = ns / scale
                        offset = (offset - centroid) * f + centroid + pan
                        scale = ns
                    }
                },
        ) {
            if (w == null) Text("جارٍ تجهيز الجدار…", Modifier.align(Alignment.Center))
            else if (w.tiles.isEmpty()) Text("لا توجد صور لعرضها.", Modifier.align(Alignment.Center))
            else {
                val x0 = -offset.x / scale; val y0 = -offset.y / scale
                val x1 = x0 + view.width / scale; val y1 = y0 + view.height / scale
                // Island titles (only when the island is big enough on screen to be readable).
                w.islands.forEach { isl ->
                    if (isl.x < x1 && isl.x + isl.w > x0 && isl.y < y1 && isl.y + isl.h > y0 && isl.w * scale > 140f) {
                        Text("${isl.title} (${isl.count})", Modifier.offset { IntOffset((isl.x * scale + offset.x).roundToInt(), (isl.y * scale + offset.y).roundToInt()) },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
                // Only tiles inside the viewport are composed (cap 500); tiny tiles are plain blocks, no decoding.
                var shown = 0
                for (t in w.tiles) {
                    if (shown >= 500) break
                    if (t.x > x1 || t.x + t.size < x0 || t.y > y1 || t.y + t.size < y0) continue
                    shown++
                    val px = t.size * scale
                    val m = Modifier.offset { IntOffset((t.x * scale + offset.x).roundToInt(), (t.y * scale + offset.y).roundToInt()) }
                        .size(with(density) { px.toDp() }).alpha(t.alpha)
                    if (px < 28f) Box(m.background(Color(0xFF9E9E9E))) else WallThumb(t.path, m)
                }
            }
        }
    }
}
