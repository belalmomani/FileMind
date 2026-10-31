package com.filemind.smartmanager.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.GeoMap
import com.filemind.smartmanager.core.WallPhoto
import com.filemind.smartmanager.core.WallRequest
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Photo map (OPTIONAL). Nothing leaves the phone: locations are read from the photos' own metadata, grouped on the device, and drawn on a plain
 * coordinate grid with NO map service and no network request. It stays off until the user agrees on the consent screen.
 */
@Composable
fun PhotoMapScreen(vm: AppViewModel, onBack: () -> Unit) {
    val enabled by vm.settings.photoMapEnabled.collectAsState(initial = false)
    val req = remember { vm.wallRequest }
    if (!enabled) MapConsent(vm, onBack) else MapBody(vm, req, onBack)
}

@Composable
private fun MapConsent(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { vm.settings.setPhotoMapEnabled(true) } else denied = true
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onBack) { Text("رجوع") }
        Text("خريطة الصور (اختيارية)", style = MaterialTheme.typography.titleLarge)
        Text("بيانات الموقع حساسة، لذلك الميزة مطفأة حتى توافق:")
        Text("• تقرأ الميزة الموقع المخزَّن داخل صورك نفسها (بيانات EXIF) على هذا الجهاز فقط.")
        Text("• تُجمَّع الصور القريبة وتُرسَم على لوحة إحداثيات محلية. لا تُستخدم أي خدمة خرائط، ولا يُرسَل موقع أو صورة إلى أي جهة.")
        Text("• تُحفظ المواقع المقروءة في ملف خاص بالتطبيق لتسريع الفتح لاحقاً، ويمكنك إيقاف الميزة وحذف هذه البيانات من الإعدادات في أي وقت.")
        Text("• يطلب أندرويد منك صلاحية «الوصول إلى مواقع الوسائط» لتُقرأ مواقع صور المعرض.")
        Button({
            if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) else scope.launch { vm.settings.setPhotoMapEnabled(true) }
        }) { Text("أوافق وأفعّل خريطة الصور") }
        OutlinedButton(onBack) { Text("ليس الآن") }
        if (denied) Text("لم تُمنح الصلاحية فلم تُفعَّل الميزة. يمكنك المحاولة مرة أخرى متى شئت.", color = MaterialTheme.colorScheme.error)
    }
}

private fun niceStep(minDeg: Double): Double =
    listOf(0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 45.0, 90.0).firstOrNull { it >= minDeg } ?: 90.0

@Composable
private fun MapBody(vm: AppViewModel, req: WallRequest?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val geo by vm.geoIndex.collectAsState()
    val progress by vm.geoProgress.collectAsState()
    val thumbDp by vm.thumbDp.collectAsState()
    LaunchedEffect(req) { req?.let { vm.analyzeGeo(it.photos) } }
    DisposableEffect(Unit) { onDispose { vm.stopGeoAnalysis() } }

    val photos = req?.photos ?: emptyList()
    var year by remember { mutableStateOf<Int?>(null) }
    var showNone by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val located = remember(geo) { photos.mapNotNull { p -> geo[p.path]?.takeIf { it.valid }?.let { p to it } } }
    val noneAll = remember(geo) { photos.filter { p -> geo[p.path]?.valid == false } }
    val unread = photos.size - located.size - noneAll.size      // not analyzed yet, or unreadable (e.g. permission)
    val years = remember(located) { GeoMap.years(located.map { it.first }) }
    val filtered = remember(located, year) { year?.let { y -> located.filter { GeoMap.yearOf(it.first.dateMs) == y } } ?: located }
    val none = remember(noneAll, year) { year?.let { y -> noneAll.filter { GeoMap.yearOf(it.dateMs) == y } } ?: noneAll }

    var view by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(10f) }          // pixels per degree
    var offset by remember { mutableStateOf(Offset.Zero) } // screen = (lon * scale + offset.x, -lat * scale + offset.y)

    fun fit() {
        val b = GeoMap.bounds(filtered.map { it.second }) ?: return
        if (view.width == 0 || view.height == 0) return
        val spanLon = max(b[3] - b[2], 0.02); val spanLat = max(b[1] - b[0], 0.02)
        val s = min(view.width / (spanLon * 1.3), view.height / (spanLat * 1.3)).toFloat().coerceIn(0.5f, 300000f)
        scale = s
        offset = Offset((view.width / 2f - ((b[2] + b[3]) / 2).toFloat() * s), (view.height / 2f + ((b[0] + b[1]) / 2).toFloat() * s))
        selected = null
    }
    LaunchedEffect(view, year, filtered.isEmpty()) { fit() }

    val level = (ln(scale.toDouble()) / ln(1.5)).roundToInt()
    val clusters = remember(filtered, level) { GeoMap.cluster(filtered, GeoMap.cellFor(1.5.pow(level))) }
    val clustersNow by rememberUpdatedState(clusters)
    LaunchedEffect(level, year) { selected = null }

    fun diameterDp(count: Int) = min(28f + 10f * sqrt(count.toFloat()), 76f)
    fun open(p: WallPhoto) {
        vm.recordOpen(p.path); try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p.path), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { vm.message.value = "لا يوجد تطبيق لفتح هذه الصورة" }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }
            Text("خريطة الصور", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            TextButton({ fit() }) { Text("ملاءمة") }
        }
        progress?.let { (d, t) ->
            Text("قراءة مواقع الصور: $d من $t (على جهازك فقط)", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(year == null, { year = null }, { Text("كل السنين") }) }
            items(years) { y -> FilterChip(year == y, { year = if (year == y) null else y }, { Text("$y") }) }
            item { FilterChip(showNone, { showNone = !showNone; selected = null }, { Text("بدون موقع (${none.size})") }) }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0x11808080)).onSizeChanged { view = it }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        var best = -1; var bestD = Float.MAX_VALUE
                        clustersNow.forEachIndexed { i, c ->
                            val sx = (c.lon * scale + offset.x).toFloat(); val sy = (-c.lat * scale + offset.y).toFloat()
                            val d = (Offset(sx, sy) - pos).getDistance()
                            val reach = with(density) { (diameterDp(c.photos.size) / 2f + 10f).dp.toPx() }
                            if (d <= reach && d < bestD) { best = i; bestD = d }
                        }
                        selected = if (best >= 0) best else null
                        if (best >= 0) showNone = false
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.5f, 300000f)
                        val f = ns / scale
                        offset = (offset - centroid) * f + centroid + pan
                        scale = ns
                    }
                },
        ) {
            val step = niceStep(60.0 / scale)
            Canvas(Modifier.fillMaxSize()) {
                val line = Color(0x33808080)
                val lon0 = (0f - offset.x) / scale; val lon1 = (size.width - offset.x) / scale
                val latTop = offset.y / scale; val latBottom = (offset.y - size.height) / scale
                var x = floor(lon0 / step) * step
                while (x <= lon1) { val sx = (x * scale + offset.x).toFloat(); drawLine(line, Offset(sx, 0f), Offset(sx, size.height)); x += step }
                var y = floor(latBottom / step) * step
                while (y <= latTop) { val sy = (-y * scale + offset.y).toFloat(); drawLine(line, Offset(0f, sy), Offset(size.width, sy)); y += step }
            }
            Text("شبكة كل ${step}° · لا توجد خريطة خلفية ولا اتصال بأي خدمة", Modifier.align(Alignment.BottomStart).padding(6.dp), style = MaterialTheme.typography.labelSmall)
            if (filtered.isEmpty()) Text(
                if (progress != null) "جارٍ قراءة المواقع…" else "لا توجد صور بموقع معروف" + if (unread > 0) " (تعذرت قراءة $unread صورة؛ تحقق من صلاحية مواقع الوسائط)" else "",
                Modifier.align(Alignment.Center).padding(16.dp),
            )
            var shown = 0
            clusters.forEachIndexed { i, c ->
                if (shown >= 300) return@forEachIndexed
                val sx = (c.lon * scale + offset.x).toFloat(); val sy = (-c.lat * scale + offset.y).toFloat()
                val dia = diameterDp(c.photos.size)
                val r = with(density) { (dia / 2f).dp.toPx() }
                if (sx < -r || sy < -r || sx > view.width + r || sy > view.height + r) return@forEachIndexed
                shown++
                Box(
                    Modifier.offset { IntOffset((sx - r).roundToInt(), (sy - r).roundToInt()) }.size(dia.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
                        .border(if (selected == i) 3.dp else 1.dp, if (selected == i) Color.White else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${c.photos.size}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge) }
            }
        }
        val sel = selected?.let { clusters.getOrNull(it) }
        val panel: List<WallPhoto> = when { sel != null -> sel.photos; showNone -> none; else -> emptyList() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                when {
                    sel != null -> "${sel.photos.size} صورة · موقع تقريبي ${GeoMap.fmt(sel.lat, sel.lon)}"
                    showNone -> "بدون بيانات موقع · ${none.size} صورة"
                    else -> "${filtered.size} صورة بموقع · اضغط فقاعة لعرض صورها، وكبّر بإصبعين"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (panel.isNotEmpty()) ThumbSizeBar(vm)
            if (panel.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                items(panel.take(300), key = { it.path }) { p -> WallThumb(p.path, Modifier.size(thumbDp.coerceIn(56, 260).dp).clip(RoundedCornerShape(8.dp)).clickable { open(p) }, sizeDp = thumbDp.coerceIn(56, 260)) }
            }
            if (unread > 0 && progress == null) Text("تعذرت قراءة بيانات الموقع من $unread صورة (لا يُعدّ هذا «بدون موقع»).", style = MaterialTheme.typography.labelSmall)
            Text("كل المعالجة على جهازك. لا تُرسَل المواقع إلى أي خدمة.", style = MaterialTheme.typography.labelSmall)
        }
    }
}
