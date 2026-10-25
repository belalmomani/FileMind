package com.filemind.smartmanager.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(vm: AppViewModel, title: String, key: String, base: (FileEntry, Long) -> Boolean, initial: FileFilter?, onBack: () -> Unit, openWall: () -> Unit = {}, openTime: () -> Unit = {}) {
    val ctx = LocalContext.current
    val all by vm.files.collectAsState()
    val msg by vm.message.collectAsState()
    val now = remember { System.currentTimeMillis() }
    var filter by remember { mutableStateOf(FileFilter()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(key) { filter = initial ?: vm.loadFilter(key); loaded = true }
    LaunchedEffect(filter, loaded) { if (loaded && initial == null) vm.saveFilter(key, filter) }

    val visible = remember(all, filter) { filter.apply(all.filter { base(it, now) }) }
    val selected: SnapshotStateList<String> = remember { mutableStateListOf() }
    val selEntries = visible.filter { it.path in selected }
    val showThumbs by vm.settings.showThumbs.collectAsState(initial = true)
    val askBeforeDelete by vm.settings.confirmDelete.collectAsState(initial = true)
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf(true) }
    var showRename by remember { mutableStateOf(false) }
    var pendingMedia by remember { mutableStateOf<List<FileEntry>>(emptyList()) }

    BackHandler(selected.isNotEmpty()) { selected.clear() }

    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.transfer(selEntries, uri, pendingMove) { selected.clear() }
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.logDelete(pendingMedia, res.resultCode == Activity.RESULT_OK, if (res.resultCode != Activity.RESULT_OK) "ألغى المستخدم أو رفض النظام" else null)
        selected.clear()
    }

    fun performDeleteNow(entries: List<FileEntry>) {
        val cr = ctx.contentResolver
        val media = entries.filter { Uri.parse(it.path).authority == "media" }
        val saf = entries - media.toSet()
        if (saf.isNotEmpty()) {
            val ok = saf.all { runCatching { DocumentsContract.deleteDocument(cr, Uri.parse(it.path)) }.getOrDefault(false) }
            vm.logDelete(saf, ok, if (!ok) "تعذر حذف بعض الملفات" else null)
        }
        // Real media (image/audio/video) uses the system confirmation; subtitles/documents/unknown only work through a granted folder.
        val (system, other) = vm.splitMedia(media)
        if (other.isNotEmpty()) vm.deleteViaGrantedFolders(other)
        if (system.isNotEmpty()) {
            pendingMedia = system
            if (Build.VERSION.SDK_INT >= 30) {
                val sender = vm.buildDeleteRequest(system)   // never throws; falls back to granted folders and reports the reason
                if (sender != null) {
                    try { deleteLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                    catch (ex: Exception) {
                        vm.logDelete(system, false, "تعذر فتح نافذة النظام: ${ex.javaClass.simpleName}")
                        vm.message.value = "تعذر فتح نافذة الحذف (${ex.javaClass.simpleName})."
                        selected.clear()
                    }
                } else selected.clear()
            } else {
                val ok = system.all { runCatching { cr.delete(Uri.parse(it.path), null, null) > 0 }.getOrDefault(false) }
                vm.logDelete(system, ok, if (!ok) "رفض النظام الحذف" else null)
            }
        }
        if (system.isEmpty()) selected.clear()
    }

    // Trash first (when on and a granted folder covers the files); whatever is left continues with the old flow.
    fun performDelete(entries: List<FileEntry>) {
        vm.deleteWithTrash(entries) { rest -> if (rest.isEmpty()) selected.clear() else performDeleteNow(rest) }
    }

    fun open(e: FileEntry) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension ?: "") ?: "*/*"
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.path), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (ex: Exception) { vm.message.value = "لا يوجد تطبيق متوافق لفتح هذا الملف" }
    }
    fun share(list: List<FileEntry>) {
        val uris = ArrayList(list.map { Uri.parse(it.path) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "مشاركة"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "$title (${visible.size})" else "محدد: ${selected.size}") },
                navigationIcon = { TextButton(onBack) { Text("رجوع") } },
                actions = {
                    if (selected.isEmpty() && visible.any { it.type == FileType.IMAGE }) TextButton({
                        val imgs = visible.filter { it.type == FileType.IMAGE }
                        vm.wallRequest = WallRequest(title, imgs.map { WallPhoto(it.path, it.parent, it.exifDateMs ?: it.modifiedMs) })
                        openWall()
                    }) { Text("جدار") }
                    if (selected.isEmpty() && visible.any { it.type == FileType.IMAGE }) TextButton({
                        val imgs = visible.filter { it.type == FileType.IMAGE }
                        vm.wallRequest = WallRequest(title, imgs.map { WallPhoto(it.path, it.parent, it.exifDateMs ?: it.modifiedMs) })
                        openTime()
                    }) { Text("رحلة") }
                    TextButton({ if (selected.size == visible.size) selected.clear() else { selected.clear(); selected.addAll(visible.map { it.path }) } }) { Text("تحديد الكل") }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) BottomAppBar {
                TextButton({ share(selEntries) }) { Text("مشاركة") }
                TextButton({ pendingMove = false; destPicker.launch(null) }) { Text("نسخ") }
                TextButton({ pendingMove = true; destPicker.launch(null) }) { Text("نقل") }
                TextButton({ showRename = true }) { Text("تسمية") }
                TextButton({ if (askBeforeDelete) confirmDelete = true else performDelete(selEntries) }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            FilterBar(vm, filter, { filter = it }, onReset = { filter = FileFilter(); vm.resetFilter(key) })
            val d = filter.describe()
            if (d.isNotEmpty()) Text("الشروط: " + d.joinToString(" · "), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            msg?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            if (key.startsWith("CAT_STALE")) Text("لم يتم تعديل هذه الملفات منذ ${key.substringAfterLast('_')} أشهر. هل تريد فتحها أو نقلها أو حذفها أو الاحتفاظ بها؟", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            if (visible.isEmpty()) Text("لا توجد ملفات مطابقة. (إن لم تمنح صلاحية أو مجلداً فقد لا تظهر ملفات.)", Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.path }) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { if (selected.isNotEmpty()) { if (e.path in selected) selected.remove(e.path) else selected.add(e.path) } else open(e) },
                                onLongClick = { if (e.path in selected) selected.remove(e.path) else selected.add(e.path) })
                            .background(if (e.path in selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (showThumbs && e.type == FileType.IMAGE) WallThumb(e.path, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
                        else Box(Modifier.size(14.dp).clip(CircleShape).background(Color(if (e.isHidden) FileType.HIDDEN_ARGB else e.type.argb)))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                            Text("${fmtSize(e.size)} · ${fmtDate(e.modifiedMs)} · ${e.parent}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (showRename) RenameDialog(vm, selEntries, onDismiss = { showRename = false }, onDone = { showRename = false; selected.clear() })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف ${selEntries.size} ملف؟") },
        text = { Text("لا يمكن التراجع عن الحذف من داخل التطبيق.") },
        confirmButton = { TextButton({ confirmDelete = false; performDelete(selEntries) }) { Text("حذف") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("إلغاء") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(vm: AppViewModel, f: FileFilter, set: (FileFilter) -> Unit, onReset: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    var showSave by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var fname by remember { mutableStateOf("") }
    val named by vm.namedFilters.collectAsState()
    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(f.query, { set(f.copy(query = it)) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("بحث بالاسم") })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(f.minSize == 5 * MB, { set(f.copy(minSize = if (f.minSize == 5 * MB) null else 5 * MB)) }, { Text("≥ 5MB") }) }
            item { FilterChip(f.minSize == 100 * MB, { set(f.copy(minSize = if (f.minSize == 100 * MB) null else 100 * MB)) }, { Text("≥ 100MB") }) }
            item { FilterChip(f.modifiedBeforeMs != null, { set(f.copy(modifiedBeforeMs = if (f.modifiedBeforeMs != null) null else now - 183 * DAY_MS)) }, { Text("قبل 6 أشهر") }) }
            item { FilterChip(!f.includeHidden, { set(f.copy(includeHidden = !f.includeHidden)) }, { Text("إخفاء المخفية") }) }
            item { FilterChip(f.onlyNoExtension, { set(f.copy(onlyNoExtension = !f.onlyNoExtension)) }, { Text("بلا امتداد") }) }
            item { FilterChip(true, { set(f.copy(combine = if (f.combine == Combine.AND) Combine.OR else Combine.AND)) }, { Text(if (f.combine == Combine.AND) "الشروط: AND" else "الشروط: OR") }) }
            item { FilterChip(true, { set(f.copy(sortBy = SortBy.entries[(f.sortBy.ordinal + 1) % SortBy.entries.size])) }, { Text("فرز: " + when (f.sortBy) { SortBy.NAME -> "الاسم"; SortBy.SIZE -> "الحجم"; SortBy.MODIFIED -> "التاريخ" }) }) }
            item { FilterChip(true, { set(f.copy(descending = !f.descending)) }, { Text(if (f.descending) "↓ تنازلي" else "↑ تصاعدي") }) }
            item { FilterChip(f.dateSource == DateSource.EXIF, { set(f.copy(dateSource = if (f.dateSource == DateSource.EXIF) DateSource.MODIFIED else DateSource.EXIF)) }, { Text("تاريخ الالتقاط (EXIF)") }) }
            item { AssistChip({ showSave = true }, { Text("حفظ الفلتر") }) }
            item { AssistChip({ showList = true }, { Text("الفلاتر المحفوظة") }) }
            item { AssistChip(onReset, { Text("إعادة ضبط") }) }
        }
        OutlinedTextField(f.folderContains, { set(f.copy(folderContains = it)) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("المجلد أو المصدر يحتوي…") })
        if (showSave) AlertDialog(
            onDismissRequest = { showSave = false }, title = { Text("اسم الفلتر (نفس الاسم يستبدل المحفوظ)") },
            text = { OutlinedTextField(fname, { fname = it }, singleLine = true) },
            confirmButton = { TextButton({ if (fname.isNotBlank()) { vm.saveNamedFilter(fname.trim(), f); fname = ""; showSave = false } }) { Text("حفظ") } },
            dismissButton = { TextButton({ showSave = false }) { Text("إلغاء") } },
        )
        if (showList) AlertDialog(
            onDismissRequest = { showList = false }, title = { Text("الفلاتر المحفوظة") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (named.isEmpty()) Text("لا توجد فلاتر محفوظة.")
                    named.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ set(FileFilter.fromJson(n.json)); showList = false }, Modifier.weight(1f)) { Text(n.name) }
                            TextButton({ vm.saveNamedFilter(n.name + " (نسخة)", FileFilter.fromJson(n.json)) }) { Text("نسخ") }
                            TextButton({ vm.deleteNamedFilter(n) }) { Text("حذف") }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showList = false }) { Text("إغلاق") } },
        )
    }
}
