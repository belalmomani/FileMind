package com.filemind.smartmanager.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.FileType

@Composable
fun ArchivesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val st by vm.archive.collectAsState()
    BackHandler(st != null) { vm.archive.value = null }
    if (st == null) ArchiveList(vm, onBack) else ArchiveDetail(vm, onBack = { vm.archive.value = null })
}

@Composable
private fun ArchiveList(vm: AppViewModel, onBack: () -> Unit) {
    val files by vm.files.collectAsState()
    val archives = remember(files) { files.filter { it.type == FileType.ARCHIVE }.sortedByDescending { it.modifiedMs ?: 0L } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onBack) { Text("رجوع") }
        Text("مختبر الأرشيفات", style = MaterialTheme.typography.titleLarge)
        Text("ZIP: عرض واستخراج آمن وإنشاء من المتصفح. 7Z وRAR: عرض المحتويات والبحث فقط (بلا استخراج). RAR5 والأرشيفات المحمية بكلمة مرور غير مدعومة. لا يُحذف أي أرشيف.", style = MaterialTheme.typography.bodySmall)
        // TODO: RAR/7Z extraction, archive-vs-archive comparison.
        if (archives.isEmpty()) Text("لا توجد أرشيفات في الملفات المفحوصة. أضف مجلداً من الشاشة الرئيسية.", Modifier.padding(16.dp))
        LazyColumn {
            items(archives, key = { it.path }) { e ->
                Column(Modifier.fillMaxWidth().clickable { vm.openArchive(e) }.padding(vertical = 8.dp)) {
                    Text(e.name, maxLines = 1)
                    Text("${e.extension?.uppercase()} · ${fmtSize(e.size)} · ${fmtDate(e.modifiedMs)} · ${e.parent}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ArchiveDetail(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by vm.archive.collectAsState()
    val msg by vm.message.collectAsState()
    val s = st ?: return
    var q by remember(s) { mutableStateOf("") }
    val sel = remember(s) { mutableStateListOf<String>() }
    var all by remember { mutableStateOf(false) }
    val canExtract = s.entry.extension == "zip"   // 7Z/RAR are listing-only
    val shown = remember(s, q) { s.items.filter { !it.isDir && (q.isBlank() || it.name.contains(q, true)) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.extractArchive(s.entry, if (all) null else sel.toSet(), uri) {}
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onBack) { Text("رجوع") }
        Text(s.entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text("${fmtSize(s.entry.size)} · ${fmtDate(s.entry.modifiedMs)} · ${s.items.count { !it.isDir }} ملف", style = MaterialTheme.typography.bodySmall)
        s.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (s.items.isNotEmpty()) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("بحث داخل الأرشيف") })
            if (!canExtract) Text("هذه الصيغة للعرض فقط: لا يمكن استخراجها من التطبيق بعد.", style = MaterialTheme.typography.bodySmall)
            if (canExtract) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ all = true; picker.launch(null) }) { Text("استخراج الكل") }
                OutlinedButton({ all = false; picker.launch(null) }, enabled = sel.isNotEmpty()) { Text("استخراج المحدد (${sel.size})") }
            }
            if (canExtract) Text("الملفات الموجودة في الوجهة لا تُستبدل: تُتجاوز وتُحصى في النتيجة.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.name }) { it ->
                Row(Modifier.fillMaxWidth().clickable(enabled = canExtract) { if (it.name in sel) sel.remove(it.name) else sel.add(it.name) }, verticalAlignment = Alignment.CenterVertically) {
                    if (canExtract) Checkbox(it.name in sel, { c -> if (c) sel.add(it.name) else sel.remove(it.name) })
                    Column { Text(it.name, maxLines = 1); Text(fmtSize(it.size), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
