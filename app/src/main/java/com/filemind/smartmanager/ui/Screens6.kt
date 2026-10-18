package com.filemind.smartmanager.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.SourceRef
import com.filemind.smartmanager.core.*

private val zipMimes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

private fun statusLabel(s: CompareStatus, missingMode: Boolean) = when (s) {
    CompareStatus.ONLY_A -> if (missingMode) "مفقود من الحالي" else "في الأول فقط"
    CompareStatus.ONLY_B -> if (missingMode) "جديد (غير موجود في المرجع)" else "في الثاني فقط"
    CompareStatus.IDENTICAL -> if (missingMode) "مطابق للمرجع" else "متطابق المحتوى"
    CompareStatus.DIFFERENT -> if (missingMode) "تغيّر عن المرجع" else "الاسم نفسه والمحتوى مختلف"
    CompareStatus.UNREADABLE -> "تعذر فحصه (ليس مفقوداً بالضرورة)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res by vm.compareResult.collectAsState()
    val busy by vm.compareBusy.collectAsState()
    var missingMode by remember { mutableStateOf(false) }
    var a by remember { mutableStateOf<SourceRef?>(null) }
    var b by remember { mutableStateOf<SourceRef?>(null) }
    var show by remember { mutableStateOf(CompareStatus.entries.toSet()) }

    fun folder(uri: Uri) = SourceRef(uri, false, DocumentsContract.getTreeDocumentId(uri))
    fun zip(uri: Uri) = SourceRef(uri, true, uri.lastPathSegment ?: "zip")
    val treeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val fa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, treeFlags) }; a = folder(u) } }
    val fb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, treeFlags) }; b = folder(u) } }
    val za = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) a = zip(u) }
    val zb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) b = zip(u) }

    val rows = res?.rows.orEmpty()
    val counts = remember(rows) { rows.groupingBy { it.status }.eachCount() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            TextButton(onBack) { Text("رجوع") }
            Text("مقارنة المصادر / كشف المفقود", style = MaterialTheme.typography.titleLarge)
            Text("للقراءة فقط: لا يُحذف أو يُعدَّل أي ملف أو أرشيف.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!missingMode, { missingMode = false }, { Text("مقارنة") })
                FilterChip(missingMode, { missingMode = true }, { Text("كشف المفقود") })
            }
            Text(if (missingMode) "المصدر الأول = المرجع، والثاني = الحالي." else "قارن محتوى مصدرين (مجلدين أو أرشيفين أو مزيجاً).", style = MaterialTheme.typography.bodySmall)
            Text(if (missingMode) "المرجع: ${a?.label ?: "—"}" else "الأول: ${a?.label ?: "—"}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ fa.launch(null) }) { Text("مجلد") }; OutlinedButton({ za.launch(zipMimes) }) { Text("أرشيف ZIP") } }
            Text(if (missingMode) "الحالي: ${b?.label ?: "—"}" else "الثاني: ${b?.label ?: "—"}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { OutlinedButton({ fb.launch(null) }) { Text("مجلد") }; OutlinedButton({ zb.launch(zipMimes) }) { Text("أرشيف ZIP") } }
            Button({ vm.runCompare(a!!, b!!) }, enabled = a != null && b != null && !busy) { Text(if (busy) "جارٍ المقارنة…" else "ابدأ") }
            Text("RAR و7Z غير مدعومين. المجلدات الكبيرة قد تستغرق وقتاً لأن المحتوى يُقارن ببصمة SHA-256.", style = MaterialTheme.typography.bodySmall)
            // TODO: merge plan for several archives (conflict-aware) — comparison only for now.
            res?.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (res != null && rows.isEmpty() && res?.note == null) Text("لا توجد ملفات في المصدرين.")
            if (rows.isNotEmpty()) {
                Text("النتيجة: " + CompareStatus.entries.joinToString(" · ") { "${statusLabel(it, missingMode)}: ${counts[it] ?: 0}" }, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CompareStatus.entries.toList()) { st -> FilterChip(st in show, { show = if (st in show) show - st else show + st }, { Text(statusLabel(st, missingMode)) }) }
                }
            }
        }
        items(rows.filter { it.status in show }.take(500), key = { it.path }) { r ->
            Column(Modifier.fillMaxWidth()) {
                Text(r.path, maxLines = 1)
                Text(statusLabel(r.status, missingMode) + (r.a?.let { " · أ: ${fmtSize(it.size)}" } ?: "") + (r.b?.let { " · ب: ${fmtSize(it.size)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

/** Interactive squarified treemap of the largest folders (by real scanned size). Tap a block for details. */
@Composable
fun StorageTreemap(files: List<FileEntry>, onOpenFolder: (String) -> Unit) {
    val byFolder = remember(files) { files.groupBy { it.parent } }
    val ranked = remember(byFolder) { byFolder.entries.map { (p, l) -> p to l }.sortedByDescending { e -> e.second.sumOf { it.size } } }
    val top = ranked.take(30)
    val rest = ranked.drop(30)
    val items = remember(files) { top.map { (p, l) -> p.ifBlank { "(بدون مسار)" } to l.sumOf { it.size } } + (if (rest.isNotEmpty()) listOf("أخرى (${rest.size} مجلد)" to rest.sumOf { e -> e.second.sumOf { it.size } }) else emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp)) {
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { maxHeight.toPx() }
        val rects = remember(items, wPx, hPx) { Treemap.layout(items, wPx, hPx) }
        rects.forEach { r ->
            val folderFiles = top.firstOrNull { (p, _) -> p.ifBlank { "(بدون مسار)" } == r.label }?.second
            val color = folderFiles?.let { Color(FolderPalette.of(it).colors.first()) } ?: Color(0xFF9A9A94)
            Box(
                Modifier.offset { IntOffset(r.x.toInt(), r.y.toInt()) }
                    .size(with(density) { (r.w - 2f).coerceAtLeast(1f).toDp() }, with(density) { (r.h - 2f).coerceAtLeast(1f).toDp() })
                    .background(if (selected == r.label) color else color.copy(alpha = 0.8f))
                    .clickable { selected = r.label }.padding(3.dp)
            ) { if (r.w > 90f && r.h > 40f) Text(r.label.substringAfterLast('/').substringAfterLast(':'), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
    }
    selected?.let { label ->
        val list = top.firstOrNull { (p, _) -> p.ifBlank { "(بدون مسار)" } == label }?.second
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (list != null) {
                    Text("${list.size} ملف · ${fmtSize(list.sumOf { it.size })}")
                    Text(list.groupBy { it.type }.entries.sortedByDescending { e -> e.value.sumOf { it.size } }.joinToString(" · ") { (t, l) -> "${t.name}: ${l.size}" }, style = MaterialTheme.typography.bodySmall)
                    TextButton({ onOpenFolder(list.first().parent) }) { Text("عرض ملفات هذا المجلد") }
                } else Text("مجلدات صغيرة مجمّعة.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
