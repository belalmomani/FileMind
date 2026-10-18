package com.filemind.smartmanager.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.*

private fun typeAr(t: FileType) = when (t) {
    FileType.IMAGE -> "صور"; FileType.VIDEO -> "فيديو"; FileType.AUDIO -> "صوتيات"; FileType.PDF -> "PDF وكتب"
    FileType.DOCUMENT -> "مستندات"; FileType.ARCHIVE -> "أرشيفات"; FileType.APK -> "APK"; FileType.UNKNOWN -> "أخرى"
}

/** Shared delete helper: SAF documents are deleted directly; MediaStore items go through the system confirmation request. */
@Composable
fun rememberDeleter(vm: AppViewModel, onFinished: () -> Unit): (List<FileEntry>) -> Unit {
    val ctx = LocalContext.current
    var pendingMedia by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.logDelete(pendingMedia, res.resultCode == Activity.RESULT_OK, if (res.resultCode != Activity.RESULT_OK) "ألغى المستخدم أو رفض النظام" else null)
        onFinished()
    }
    return { entries ->
        val cr = ctx.contentResolver
        val media = entries.filter { Uri.parse(it.path).authority == "media" }
        val saf = entries - media.toSet()
        if (saf.isNotEmpty()) {
            val ok = saf.all { runCatching { DocumentsContract.deleteDocument(cr, Uri.parse(it.path)) }.getOrDefault(false) }
            vm.logDelete(saf, ok, if (!ok) "تعذر حذف بعض الملفات" else null)
        }
        if (media.isNotEmpty()) {
            pendingMedia = media
            if (Build.VERSION.SDK_INT >= 30) {
                launcher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(cr, media.map { Uri.parse(it.path) }).intentSender).build())
            } else {
                val ok = media.all { runCatching { cr.delete(Uri.parse(it.path), null, null) > 0 }.getOrDefault(false) }
                vm.logDelete(media, ok, if (!ok) "رفض النظام الحذف" else null); onFinished()
            }
        } else onFinished()
    }
}

@Composable
fun StorageScreen(vm: AppViewModel, openType: (FileType) -> Unit, openFolder: (String) -> Unit, onBack: () -> Unit) {
    val files by vm.files.collectAsState()
    val byType = remember(files) { files.groupBy { it.type }.map { (t, l) -> Triple(t, l.size, l.sumOf { it.size }) }.sortedByDescending { it.third } }
    val total = files.sumOf { it.size }
    val stat = remember { runCatching { StatFs(Environment.getExternalStorageDirectory().path).let { it.totalBytes to it.availableBytes } }.getOrNull() }
    val folders = remember(files) { files.groupBy { it.parent }.map { (p, l) -> Triple(p, l.size, l.sumOf { it.size }) }.sortedByDescending { it.third }.take(8) }
    val biggest = remember(files) { files.sortedByDescending { it.size }.take(10) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onBack) { Text("رجوع") }
            Text("تحليل التخزين", style = MaterialTheme.typography.titleLarge)
            stat?.let { (t, a) -> Text("سعة وحدة التخزين الأساسية: ${fmtSize(t)} · المتاح: ${fmtSize(a)} · المستخدم: ${fmtSize(t - a)}") }
            Text("المحسوب أدناه هو الملفات التي استطاع التطبيق فحصها فقط (${files.size} ملف، ${fmtSize(total)}). ما لم يُفحص غير محسوب ولا يُعتبر صفراً.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            if (total > 0) Row(Modifier.fillMaxWidth().height(90.dp)) {
                byType.forEach { (t, _, sz) ->
                    Box(Modifier.weight(sz.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Color(t.argb)).clickable { openType(t) }.padding(4.dp)) {
                        if (sz * 100 / total >= 8) Text(typeAr(t), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Text("خريطة المجلدات (المساحة ∝ الحجم الفعلي للملفات المفحوصة)", style = MaterialTheme.typography.titleMedium)
            StorageTreemap(files, openFolder)
        }
        items(byType) { (t, n, sz) ->
            Row(Modifier.fillMaxWidth().clickable { openType(t) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(Color(t.argb)))
                Text(typeAr(t), Modifier.weight(1f)); Text("$n ملف · ${fmtSize(sz)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("أكبر المجلدات", style = MaterialTheme.typography.titleMedium) }
        items(folders) { (p, n, sz) -> Text("${p.ifBlank { "(بدون مسار)" }} — $n ملف · ${fmtSize(sz)}", maxLines = 1, style = MaterialTheme.typography.bodySmall) }
        item { Text("أكبر الملفات", style = MaterialTheme.typography.titleMedium) }
        items(biggest) { e -> Text("${e.name} — ${fmtSize(e.size)}", maxLines = 1, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun DuplicatesScreen(vm: AppViewModel, onBack: () -> Unit, openSimilar: () -> Unit) {
    val groups by vm.duplicates.collectAsState()
    val prog by vm.dupProgress.collectAsState()
    val sel = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }
    val delete = rememberDeleter(vm) { sel.clear(); vm.duplicates.value = null }
    val removesAllCopies = groups?.any { g -> g.all { it.path in sel } } == true
    val selected = groups?.flatten()?.filter { it.path in sel }.orEmpty()

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }; Spacer(Modifier.weight(1f))
            OutlinedButton(openSimilar) { Text("صور متشابهة") }
            Spacer(Modifier.width(8.dp))
            Button({ sel.clear(); vm.scanDuplicates() }, enabled = prog == null) { Text(prog ?: "فحص المكررات") }
        }
        Text("يُعتبر الملفان مكررين فقط عند تطابق الحجم وبصمة المحتوى SHA-256. لا يُحذف شيء تلقائياً.", style = MaterialTheme.typography.bodySmall)
        // TODO: folder exclusion list for the scan.
        if (groups == null && prog == null) Text("اضغط «فحص المكررات» (قد يستغرق وقتاً مع الملفات الكبيرة).")
        if (groups?.isEmpty() == true) Text("لم يُعثر على نسخ متطابقة.")
        if (removesAllCopies) Text("لا يمكن حذف كل النسخ في مجموعة؛ أبقِ نسخة واحدة على الأقل.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(groups.orEmpty()) { i, g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("مجموعة ${i + 1}: ${g.size} نسخ متطابقة × ${fmtSize(g.first().size)}", style = MaterialTheme.typography.titleSmall)
                        g.forEach { e ->
                            Row(Modifier.fillMaxWidth().clickable { if (e.path in sel) sel.remove(e.path) else sel.add(e.path) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(e.path in sel, { if (it) sel.add(e.path) else sel.remove(e.path) })
                                Column { Text(e.name, maxLines = 1); Text("${e.parent} · ${fmtDate(e.modifiedMs)}", maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
        if (sel.isNotEmpty()) Button({ confirm = true }, enabled = !removesAllCopies) { Text("حذف المحدد (${sel.size})") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("حذف ${selected.size} ملف؟") }, text = { Text("ستبقى نسخة واحدة على الأقل من كل مجموعة. لا تراجع من داخل التطبيق.") },
        confirmButton = { TextButton({ confirm = false; delete(selected) }) { Text("حذف") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
    )
}

private fun gbAr(g: GroupBy) = when (g) { GroupBy.NAME -> "الاسم"; GroupBy.YEAR -> "السنة"; GroupBy.YEAR_MONTH -> "سنة/شهر"; GroupBy.EXTENSION -> "الامتداد"; GroupBy.TYPE -> "النوع" }
private fun dsAr(d: DateSource) = when (d) { DateSource.MODIFIED -> "تاريخ التعديل"; DateSource.EXIF -> "تاريخ الالتقاط (EXIF)"; DateSource.NAME -> "سنة من الاسم" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizerScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files by vm.files.collectAsState()
    val msg by vm.message.collectAsState()
    var types by remember { mutableStateOf(setOf(FileType.ARCHIVE)) }
    var primary by remember { mutableStateOf(GroupBy.NAME) }
    var secondary by remember { mutableStateOf<GroupBy?>(GroupBy.YEAR) }
    var ds by remember { mutableStateOf(DateSource.MODIFIED) }
    var unknownFolder by remember { mutableStateOf(true) }
    var dest by remember { mutableStateOf<Uri?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val tasks by vm.tasks.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    var taskName by remember { mutableStateOf("") }

    val rule = OrganizeRule(types, primary, secondary, ds, destRoot = "", unknownDateFolder = if (unknownFolder) "تاريخ غير معروف" else null)
    val plan = remember(files, types, primary, secondary, ds, unknownFolder) { Organizer.plan(files, rule) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            dest = uri
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onBack) { Text("رجوع") }
            Text("محرك التنظيم", style = MaterialTheme.typography.titleLarge)
            Text("النوع", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(FileType.entries.filter { it != FileType.UNKNOWN }) { t -> FilterChip(t in types, { types = if (t in types) types - t else types + t }, { Text(typeAr(t)) }) }
            }
            Text("التجميع الأساسي", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(GroupBy.entries) { g -> FilterChip(primary == g, { primary = g }, { Text(gbAr(g)) }) } }
            Text("التقسيم الفرعي", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(secondary == null, { secondary = null }, { Text("بدون") }) }
                items(GroupBy.entries) { g -> FilterChip(secondary == g, { secondary = g }, { Text(gbAr(g)) }) }
            }
            Text("مصدر التاريخ", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(DateSource.entries) { d -> FilterChip(ds == d, { ds = d }, { Text(dsAr(d)) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(unknownFolder, { unknownFolder = it }); Text("  مجموعة «تاريخ غير معروف» (وإلا تُتجاوز هذه الملفات)") }
            Button({ picker.launch(null) }) { Text(if (dest == null) "اختيار مجلد الوجهة" else "الوجهة: " + (DocumentsContract.getTreeDocumentId(dest!!))) }
            HorizontalDivider()
            Text("المعاينة (لا يتم نقل شيء قبل موافقتك)", style = MaterialTheme.typography.titleMedium)
            Text("سيُنقل ${plan.moves.size} ملف إلى ${plan.newFolders.size} مجلد · تُجوز ${plan.skipped.size} (تاريخ مجهول أو تعذر الوصول)")
            if (ds == DateSource.EXIF) Text("الملفات بلا بيانات EXIF لا يُخترع لها تاريخ.", style = MaterialTheme.typography.bodySmall)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button({ confirm = true }, enabled = dest != null && plan.moves.isNotEmpty()) { Text("تنفيذ التنظيم") }
            OutlinedButton({ showSave = true }) { Text("حفظ كمهمة") }
            if (tasks.isNotEmpty()) Text("المهام المحفوظة (التحميل يعرض معاينة محدثة)", style = MaterialTheme.typography.titleSmall)
            tasks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        OrganizeRuleJson.fromJson(t.json)?.let { (r, d) ->
                            types = r.types; primary = r.primary; secondary = r.secondary; ds = r.dateSource
                            unknownFolder = r.unknownDateFolder != null; dest = d?.let { Uri.parse(it) }
                        }
                    }, Modifier.weight(1f)) { Text(t.name) }
                    TextButton({ vm.deleteTask(t) }) { Text("حذف") }
                }
            }
        }
        items(plan.moves.groupBy { it.targetDir }.entries.take(40).toList()) { (dir, list) ->
            Text("${dir.trimStart('/')} ← ${list.size} ملف", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showSave) AlertDialog(
        onDismissRequest = { showSave = false },
        title = { Text("اسم المهمة") },
        text = { OutlinedTextField(taskName, { taskName = it }, singleLine = true) },
        confirmButton = { TextButton({ if (taskName.isNotBlank()) { vm.saveTask(taskName.trim(), OrganizeRuleJson.toJson(rule, dest?.toString())); taskName = ""; showSave = false } }) { Text("حفظ") } },
        dismissButton = { TextButton({ showSave = false }) { Text("إلغاء") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("نقل ${plan.moves.size} ملف؟") },
        text = { Text("ستُنقل الملفات إلى مجلدات جديدة داخل الوجهة. الملفات المتعارضة ستسألك عن القرار. لا تراجع تلقائي.") },
        confirmButton = { TextButton({ confirm = false; Organizer.plan(vm.files.value, rule).let { p -> vm.runOrganize(p, dest!!) {} } }) { Text("تنفيذ") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
    )
}

@Composable
fun RenameDialog(vm: AppViewModel, entries: List<FileEntry>, onDismiss: () -> Unit, onDone: () -> Unit) {
    var find by remember { mutableStateOf("") }; var repl by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }; var suffix by remember { mutableStateOf("") }
    var seq by remember { mutableStateOf(false) }; var date by remember { mutableStateOf(false) }
    var trim by remember { mutableStateOf(false) }; var lower by remember { mutableStateOf(false) }
    val parents = entries.map { it.parent }.toSet()
    val siblings = remember(entries) { vm.files.value.filter { it.parent in parents }.map { it.name }.toSet() }
    val opts = RenameOptions(find.ifEmpty { null }, repl, prefix, suffix, if (seq) 1 else null, 3, if (date) "yyyy-MM-dd" else null, false, trim, lower)
    val preview = BulkRenamer.preview(entries, opts, siblings)
    val changed = preview.filter { it.newName != it.entry.name }
    val ok = changed.isNotEmpty() && BulkRenamer.canApply(preview)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعادة تسمية ${entries.size} ملف") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(find, { find = it }, singleLine = true, label = { Text("استبدال النص") })
                OutlinedTextField(repl, { repl = it }, singleLine = true, label = { Text("بـ") })
                OutlinedTextField(prefix, { prefix = it }, singleLine = true, label = { Text("إضافة في البداية") })
                OutlinedTextField(suffix, { suffix = it }, singleLine = true, label = { Text("إضافة في النهاية (قبل الامتداد)") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(seq, { seq = it }); Text("رقم تسلسلي") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(date, { date = it }); Text("تاريخ التعديل في البداية") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trim, { trim = it }); Text("إزالة المسافات") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(lower, { lower = it }); Text("أحرف صغيرة") }
                HorizontalDivider()
                preview.take(8).forEach { p -> Text("${p.entry.name} ← ${p.newName}" + if (p.collision) "  ⚠ تعارض" else "", style = MaterialTheme.typography.bodySmall, color = if (p.collision) MaterialTheme.colorScheme.error else Color.Unspecified) }
                if (preview.any { it.collision }) Text("لا يمكن التنفيذ قبل حل تعارض الأسماء.", color = MaterialTheme.colorScheme.error)
                Text("تدعم إعادة التسمية ملفات المجلدات المضافة فقط.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ vm.runRename(changed, onDone) }, enabled = ok) { Text("تنفيذ") } },
        dismissButton = { TextButton(onDismiss) { Text("إلغاء") } },
    )
}
