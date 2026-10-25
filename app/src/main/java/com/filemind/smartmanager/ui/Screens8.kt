package com.filemind.smartmanager.ui

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
import com.filemind.smartmanager.data.TrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trash: items deleted from the app are moved (not erased) into a hidden folder inside the granted folder, so they can be restored. */
@Composable
fun TrashScreen(vm: AppViewModel, onBack: () -> Unit) {
    val cr = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val roots by vm.roots.collectAsState()
    val days by vm.settings.trashDays.collectAsState(initial = 30)
    var trashItems by remember { mutableStateOf<List<TrashStore.Item>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<TrashStore.Item?>(null) }

    LaunchedEffect(roots, reload) { trashItems = null; trashItems = withContext(Dispatchers.IO) { TrashStore.list(cr, roots) } }

    fun restore(i: TrashStore.Item) = scope.launch {
        val r = withContext(Dispatchers.IO) { TrashStore.restore(cr, i) }
        msg = when (r) {
            TrashStore.Restore.OK -> "تم استرجاع ${i.record.name}"
            TrashStore.Restore.EXISTS -> "لا يمكن الاسترجاع: يوجد ملف بالاسم نفسه في المكان الأصلي. غيّر اسم أحدهما ثم أعد المحاولة"
            TrashStore.Restore.FAILED -> "تعذر الاسترجاع"
        }
        vm.logOp("استرجاع", i.record.name, "", "", "", r == TrashStore.Restore.OK, if (r != TrashStore.Restore.OK) msg else null)
        if (r == TrashStore.Restore.OK) vm.rescan()
        reload++
    }
    fun deleteForever(list: List<TrashStore.Item>) = scope.launch {
        val ok = withContext(Dispatchers.IO) { list.count { TrashStore.deleteForever(cr, it) } }
        msg = "حُذف نهائياً $ok من ${list.size}"
        vm.logOp("حذف نهائي من السلة", "${list.size} عنصر", "", "", "", ok == list.size, if (ok != list.size) "تعذر حذف بعض العناصر" else null)
        reload++
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }; Spacer(Modifier.weight(1f))
            TextButton({ confirmEmpty = true }, enabled = !trashItems.isNullOrEmpty()) { Text("إفراغ السلة") }
        }
        Text("السلة", style = MaterialTheme.typography.titleLarge)
        Text(
            if (days > 0) "العناصر المحذوفة تُنقل هنا وتُحذف نهائياً تلقائياً بعد $days يوماً. تعيش السلة في مجلد مخفي باسم ${TrashStore.TRASH_DIR} داخل المجلد الممنوح للتطبيق؛ لا تحذفه يدوياً إن أردت الاسترجاع."
            else "السلة معطّلة: الحذف نهائي. يمكن تفعيلها من الإعدادات.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("لا تُنقل إلى السلة إلا الملفات داخل مجلدات منحتَ التطبيق وصولاً إليها (إضافة مجلد). غيرها يُحذف بنافذة تأكيد النظام ولا يمكن استرجاعه.", style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        val list = trashItems
        when {
            list == null -> Text("جارٍ القراءة…")
            list.isEmpty() -> Text("السلة فارغة.", Modifier.padding(16.dp))
            else -> {
                Text("${list.size} عنصر · ${fmtSize(list.sumOf { it.record.size })}", style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    items(list, key = { it.batchDocId }) { i ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text((if (i.record.isDir) "📁 " else "") + i.record.name)
                            Text(
                                (if (i.record.deletedAtMs > 0) "حُذف: ${fmtDate(i.record.deletedAtMs)}" else "وقت الحذف غير معروف (لا يُحذف تلقائياً)") +
                                    (if (!i.record.isDir) " · ${fmtSize(i.record.size)}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (i.record.originalParentDocId.isNotEmpty()) Text("المكان الأصلي: ${i.record.originalParentDocId}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Row {
                                TextButton({ restore(i) }) { Text("استرجاع") }
                                TextButton({ confirmOne = i }) { Text("حذف نهائي", color = MaterialTheme.colorScheme.error) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false }, title = { Text("إفراغ السلة؟") },
        text = { Text("سيُحذف كل ما في السلة نهائياً ولا يمكن التراجع.") },
        confirmButton = { TextButton({ confirmEmpty = false; trashItems?.let { deleteForever(it) } }) { Text("إفراغ") } },
        dismissButton = { TextButton({ confirmEmpty = false }) { Text("إلغاء") } },
    )
    confirmOne?.let { i ->
        AlertDialog(
            onDismissRequest = { confirmOne = null }, title = { Text("حذف نهائي؟") },
            text = { Text("سيُحذف ${i.record.name} نهائياً ولا يمكن التراجع.") },
            confirmButton = { TextButton({ confirmOne = null; deleteForever(listOf(i)) }) { Text("حذف") } },
            dismissButton = { TextButton({ confirmOne = null }) { Text("إلغاء") } },
        )
    }
}
