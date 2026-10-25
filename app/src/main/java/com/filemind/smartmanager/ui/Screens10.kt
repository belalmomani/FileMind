package com.filemind.smartmanager.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.PhotoTimeline
import com.filemind.smartmanager.core.TimeSection
import com.filemind.smartmanager.core.WallPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface TItem {
    val section: Int
    class Header(override val section: Int, val label: String, val count: Int) : TItem
    class Photos(override val section: Int, val photos: List<WallPhoto>) : TItem
}

private const val COLS = 4

/**
 * Photo Time Machine: your photos as a journey from today back to the oldest one. Scroll, drag the time slider to jump between periods,
 * or press play and the timeline moves by itself. Tap a photo to open it.
 */
@Composable
fun PhotoTimeMachineScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val req = remember { vm.wallRequest }
    val sections by produceState<List<TimeSection>?>(null, req) {
        value = req?.let { r -> withContext(Dispatchers.Default) { PhotoTimeline.build(r.photos, System.currentTimeMillis()) } }
    }
    val flat = remember(sections) {
        sections?.let { list ->
            val out = ArrayList<TItem>()
            list.forEachIndexed { i, s ->
                out += TItem.Header(i, s.label, s.photos.size)
                s.photos.chunked(COLS).forEach { out += TItem.Photos(i, it) }
            }
            out
        }
    }
    val headerIndex = remember(flat) { flat?.withIndex()?.filter { it.value is TItem.Header }?.map { it.index } ?: emptyList() }
    val listState = rememberLazyListState()
    val current by remember(flat) { derivedStateOf { flat?.getOrNull(listState.firstVisibleItemIndex)?.section ?: 0 } }

    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(10f) }   // pixels per frame
    LaunchedEffect(playing, speed) {
        if (playing) {
            while (true) {
                val moved = listState.scrollBy(speed)
                if (moved == 0f) break          // reached the oldest photo
                delay(16)
            }
            playing = false
        }
    }

    fun open(p: WallPhoto) {
        playing = false
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p.path), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { vm.message.value = "لا يوجد تطبيق لفتح هذه الصورة" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }
            Text("رحلة زمنية · ${req?.photos?.size ?: 0} صورة", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        val secs = sections
        if (secs != null && secs.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(secs.getOrNull(current)?.label ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (secs.size > 1) Slider(
                    value = current.toFloat(),
                    onValueChange = { v -> playing = false; headerIndex.getOrNull(v.roundToInt())?.let { i -> scope.launch { listState.scrollToItem(i) } } },
                    valueRange = 0f..(secs.size - 1).toFloat(),
                    steps = (secs.size - 2).coerceAtLeast(0),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ playing = false; scope.launch { listState.scrollToItem(0) } }) { Text("اليوم") }
                    TextButton({ playing = false; scope.launch { listState.scrollToItem((flat?.size ?: 1) - 1) } }) { Text("الأقدم") }
                    Button({ playing = !playing }) { Text(if (playing) "إيقاف" else "تشغيل الرحلة") }
                    listOf(4f to "بطيء", 10f to "متوسط", 24f to "سريع").forEach { (v, label) -> FilterChip(speed == v, { speed = v }, { Text(label) }) }
                }
            }
        }
        when {
            sections == null -> Text("جارٍ تجهيز الرحلة…", Modifier.padding(16.dp))
            flat.isNullOrEmpty() -> Text("لا توجد صور لعرضها.", Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 8.dp)) {
                itemsIndexed(flat, key = { i, _ -> i }) { _, it ->
                    when (it) {
                        is TItem.Header -> Text("${it.label} · ${it.count}", Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                        is TItem.Photos -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            it.photos.forEach { p -> WallThumb(p.path, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { open(p) }) }
                            repeat(COLS - it.photos.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
