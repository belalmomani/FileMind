package com.filemind.smartmanager.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.ColorGroup
import com.filemind.smartmanager.core.ColorMatch
import com.filemind.smartmanager.core.WallPhoto
import kotlin.math.roundToInt

/**
 * Color explorer: browse photos by their dominant color. Tap a group, or slide a finger along the rainbow bar to pick any hue and widen or narrow its range.
 * Results are APPROXIMATE (colors are estimated from a tiny copy of each photo).
 */
@Composable
fun ColorExplorerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val req = remember { vm.wallRequest }
    val index by vm.colorIndex.collectAsState()
    val progress by vm.colorProgress.collectAsState()
    val thumbDp by vm.thumbDp.collectAsState()
    var group by remember { mutableStateOf<ColorGroup?>(null) }
    var hue by remember { mutableStateOf<Float?>(null) }
    var tol by remember { mutableStateOf(25f) }
    var barWidth by remember { mutableStateOf(1) }

    LaunchedEffect(req) { req?.let { vm.analyzeColors(it.photos) } }
    DisposableEffect(Unit) { onDispose { vm.stopColorAnalysis() } }

    val photos = req?.photos ?: emptyList()
    val counts = remember(index) { photos.mapNotNull { p -> index[p.path]?.let { ColorMatch.group(it) } }.groupingBy { it }.eachCount() }
    val shown = remember(index, group, hue, tol) {
        val h = hue; val g = group
        when {
            h != null -> photos.mapNotNull { p -> index[p.path]?.let { pr -> ColorMatch.hueScore(pr, h, tol).takeIf { it >= 0.12f }?.let { p to it } } }
                .sortedByDescending { it.second }.map { it.first }
            g != null -> photos.filter { p -> index[p.path]?.let { ColorMatch.group(it) == g } == true }
            else -> photos
        }
    }

    fun open(p: WallPhoto) {
        vm.recordOpen(p.path); try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p.path), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { vm.message.value = "لا يوجد تطبيق لفتح هذه الصورة" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }
            Text("مستكشف الألوان", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("${shown.size} صورة", style = MaterialTheme.typography.bodySmall)
        }
        progress?.let { (d, t) ->
            Text("تحليل الألوان: $d من $t (يمكنك الاستكشاف بما تم تحليله)", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        Text("النتائج تقريبية: الألوان تُقدَّر من نسخة صغيرة جداً من كل صورة.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(hue == null && group == null, { hue = null; group = null }, { Text("الكل (${photos.size})") }) }
            items(ColorGroup.entries.toList()) { g ->
                FilterChip(hue == null && group == g, { hue = null; group = g }, { Text("${g.titleAr} (${counts[g] ?: 0})") })
            }
        }
        if (shown.isEmpty()) Text(
            if (progress != null) "جارٍ التحليل… ستظهر الصور المطابقة تباعاً." else "لا توجد صور مطابقة. جرّب توسيع نطاق اللون.",
            Modifier.padding(16.dp),
        )
        LazyVerticalGrid(
            GridCells.Adaptive(thumbDp.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.path }) { p -> WallThumb(p.path, Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { open(p) }, sizeDp = thumbDp) }
        }
        ThumbSizeBar(vm)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            hue?.let { Text("نطاق اللون: ±${tol.roundToInt()}° (الصور الأقرب أولاً)", style = MaterialTheme.typography.bodySmall) }
            if (hue != null) Slider(tol, { tol = it }, valueRange = 10f..60f)
            Box(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(List(13) { Color.hsv((it * 30f).coerceAtMost(359f), 1f, 1f) }))
                    .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) { detectTapGestures { o -> group = null; hue = (o.x / barWidth * 360f).coerceIn(0f, 359f) } }
                    .pointerInput(Unit) { detectDragGestures { change, _ -> group = null; hue = (change.position.x / barWidth * 360f).coerceIn(0f, 359f) } },
            ) {
                hue?.let { h ->
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .offset { IntOffset((h / 360f * barWidth - 18.dp.toPx()).roundToInt(), 0) }
                            .size(36.dp).clip(CircleShape).background(Color.hsv(h, 1f, 1f)).border(3.dp, Color.White, CircleShape),
                    )
                }
            }
            Text("حرّك إصبعك على الشريط لاختيار لون.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
