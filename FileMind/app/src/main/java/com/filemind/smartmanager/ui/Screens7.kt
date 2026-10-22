package com.filemind.smartmanager.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.*
import com.filemind.smartmanager.data.BItem
import com.filemind.smartmanager.data.FolderLister
import com.filemind.smartmanager.data.SafOps
import com.filemind.smartmanager.data.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Loc(val tree: String, val docId: String, val name: String)
private data class Fav(val tree: String, val docId: String, val name: String, val isDir: Boolean)

private const val SEP = "\u0001"
private fun favKey(tree: String, docId: String, name: String, isDir: Boolean) = listOf(tree, docId, name, if (isDir) "1" else "0").joinToString(SEP)
private fun parseFav(s: String): Fav? = s.split(SEP).let { if (it.size < 4) null else Fav(it[0], it[1], it[2], it[3] == "1") }
private fun BItem.toEntry(parent: String) = FileEntry(uri, name, size, modified, parent = parent)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val scope = rememberCoroutineScope()
    val roots by vm.roots.collectAsState()
    val files by vm.files.collectAsState()
    val msg by vm.message.collectAsState()
    val favs by vm.settings.favorites.collectAsState(initial = emptySet())
    val viewMode by vm.settings.browserView.collectAsState(initial = ViewMode.LIST)
    val showHidden by vm.settings.browserShowHidden.collectAsState(initial = false)

    var stack by remember { mutableStateOf(listOf<Loc>()) }
    var result by remember { mutableStateOf<FolderLister.Result?>(null) }
    var reload by remember { mutableStateOf(0) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    val selected = remember { mutableStateListOf<String>() }
    var dialog by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var pendingMove by remember { mutableStateOf(true) }
    val cur = stack.lastOrNull()
    val byParent = remember(files) { files.groupBy { it.parent } }

    LaunchedEffect(cur, reload) {
        result = null
        if (cur != null) result = withContext(Dispatchers.IO) { FolderLister.list(cr, Uri.parse(cur.tree), cur.docId) }
    }
    BackHandler(stack.isNotEmpty() || selected.isNotEmpty()) { if (selected.isNotEmpty()) selected.clear() else stack = stack.dropLast(1) }

    val shownItems = remember(result, sortBy, showHidden) {
        result?.items.orEmpty().filter { showHidden || !it.isHidden }.sortedWith(
            compareByDescending<BItem> { it.isDir }.thenComparator { a, b ->
                when (sortBy) {
                    SortBy.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                    SortBy.SIZE -> b.size.compareTo(a.size)
                    SortBy.MODIFIED -> (b.modified ?: 0L).compareTo(a.modified ?: 0L)
                }
            })
    }
    val sel = shownItems.filter { it.docId in selected }

    fun toggle(i: BItem) { if (i.docId in selected) selected.remove(i.docId) else selected.add(i.docId) }
    fun open(i: BItem) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(i.ext ?: "") ?: i.mime ?: "*/*"
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(i.uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { vm.message.value = "لا يوجد تطبيق متوافق لفتح هذا الملف" }
    }
    fun click(i: BItem) {
        if (selected.isNotEmpty()) toggle(i)
        else if (i.isDir && cur != null) stack = stack + Loc(cur.tree, i.docId, i.name)
        else if (!i.isDir) open(i)
    }

    val askDelete by vm.settings.confirmDelete.collectAsState(initial = true)
    fun doDelete(list: List<BItem>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                list.forEach { i ->
                    val ok = runCatching { DocumentsContract.deleteDocument(cr, Uri.parse(i.uri)) }.getOrDefault(false)
                    vm.logOp("حذف", i.name, "", i.uri, "", ok, if (!ok) "تعذر الحذف" else null)
                }
            }
            selected.clear(); reload++; vm.rescan()
        }
    }

    val addRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.addRoot(uri)
        }
    }
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && cur != null) {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.transferWithFolders(Uri.parse(cur.tree), sel.filter { !it.isDir }, sel.filter { it.isDir }, cur.docId, uri, pendingMove) { selected.clear(); reload++ }
        }
    }

    Scaffold(
        bottomBar = {
            if (selected.isNotEmpty() && cur != null) BottomAppBar {
                TextButton({
                    val list = sel.filter { !it.isDir }
                    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { Uri.parse(it.uri) })).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (list.isNotEmpty()) ctx.startActivity(Intent.createChooser(i, "مشاركة")) else vm.message.value = "المشاركة للملفات فقط"
                }) { Text("مشاركة") }
                TextButton({ pendingMove = false; destPicker.launch(null) }) { Text("نسخ") }
                TextButton({ pendingMove = true; destPicker.launch(null) }) { Text("نقل") }
                if (sel.size == 1) {
                    TextButton({ textInput = sel[0].name; dialog = "rename" }) { Text("تسمية") }
                    TextButton({ dialog = "info" }) { Text("معلومات") }
                    TextButton({ val i = sel[0]; scope.launch { vm.settings.toggleFavorite(favKey(cur.tree, i.docId, i.name, i.isDir)) } }) { Text("★") }
                }
                TextButton({ if (askDelete || sel.any { it.isDir }) dialog = "delete" else doDelete(sel.toList()) }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (stack.isEmpty()) onBack() else stack = stack.dropLast(1) }) { Text(if (stack.isEmpty()) "رجوع" else "↑ أعلى") }
                Text(if (cur == null) "المتصفح" else stack.joinToString(" / ") { it.name.substringAfterLast('/').substringAfterLast(':').ifEmpty { it.name } }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                if (cur != null) {
                    TextButton({ textInput = ""; dialog = "newfolder" }) { Text("＋مجلد") }
                    TextButton({ reload++ }) { Text("تحديث") }
                }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (cur != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ViewMode.entries.toList()) { m -> FilterChip(viewMode == m, { scope.launch { vm.settings.setBrowserView(m) } }, { Text(when (m) { ViewMode.LIST -> "قائمة"; ViewMode.GRID -> "شبكة"; ViewMode.DETAILS -> "تفاصيل" }) }) }
                item { FilterChip(showHidden, { scope.launch { vm.settings.setBrowserShowHidden(!showHidden) } }, { Text("المخفية") }) }
                item { FilterChip(true, { sortBy = SortBy.entries[(sortBy.ordinal + 1) % SortBy.entries.size] }, { Text("فرز: " + when (sortBy) { SortBy.NAME -> "الاسم"; SortBy.SIZE -> "الحجم"; SortBy.MODIFIED -> "التاريخ" }) }) }
            }

            if (cur == null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        Button({ addRoot.launch(null) }) { Text("إضافة مجلد / بطاقة SD / USB") }
                        Text("على Android 11+ لا يسمح النظام بمنح الوصول لجذر الذاكرة الداخلية أو مجلد Download أو Android/data. اختر مجلدات فرعية مثل DCIM أو Documents.", style = MaterialTheme.typography.bodySmall)
                        Text("المجلدات المضافة", style = MaterialTheme.typography.titleMedium)
                        if (roots.isEmpty()) Text("لا توجد مجلدات مضافة بعد.")
                    }
                    items(roots.toList()) { r ->
                        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(r)) }.getOrDefault(r)
                        Row(Modifier.fillMaxWidth().clickable { stack = listOf(Loc(r, id, id)) }.padding(vertical = 10.dp)) { Text("📁 $id", maxLines = 1) }
                    }
                    item { Text("المفضلة", style = MaterialTheme.typography.titleMedium); if (favs.isEmpty()) Text("لا توجد مفضلة. حدّد عنصراً واضغط ★.", style = MaterialTheme.typography.bodySmall) }
                    items(favs.toList()) { k ->
                        val f = parseFav(k)
                        if (f != null) Row(Modifier.fillMaxWidth().clickable {
                            if (f.isDir) stack = listOf(Loc(f.tree, f.docId, f.name))
                            else open(BItem(f.docId, f.name, false, 0, null, DocumentsContract.buildDocumentUriUsingTree(Uri.parse(f.tree), f.docId).toString(), null))
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (f.isDir) "📁 " else "📄 ") + f.name, Modifier.weight(1f), maxLines = 1)
                            TextButton({ scope.launch { vm.settings.toggleFavorite(k) } }) { Text("إزالة") }
                        }
                    }
                }
            } else when {
                result == null -> Text("جارٍ التحميل…", Modifier.padding(16.dp))
                result?.error != null -> Text(result?.error.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                shownItems.isEmpty() -> Text(if (result?.items.orEmpty().isEmpty()) "المجلد فارغ" else "لا عناصر ظاهرة (المخفية غير معروضة).", Modifier.padding(16.dp))
                viewMode == ViewMode.GRID -> LazyVerticalGrid(GridCells.Adaptive(100.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gridItems(shownItems, key = { it.docId }) { i ->
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i.docId in selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                            .combinedClickable(onClick = { click(i) }, onLongClick = { toggle(i) }).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ItemIcon(i, byParent, 56)
                            Text(i.name, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                else -> LazyColumn {
                    items(shownItems, key = { it.docId }) { i ->
                        Row(Modifier.fillMaxWidth().background(if (i.docId in selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .combinedClickable(onClick = { click(i) }, onLongClick = { toggle(i) }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ItemIcon(i, byParent, 40)
                            Column(Modifier.weight(1f)) {
                                Text((if (favs.contains(favKey(cur.tree, i.docId, i.name, i.isDir))) "★ " else "") + i.name, maxLines = 1)
                                if (viewMode == ViewMode.DETAILS) Text(listOf(if (i.isDir) "مجلد" else (i.ext ?: "بلا امتداد"), if (i.isDir) "" else fmtSize(i.size), fmtDate(i.modified)).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                else if (!i.isDir) Text(fmtSize(i.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    val one = sel.firstOrNull()
    if (dialog == "newfolder" && cur != null) AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text("مجلد جديد") },
        text = { OutlinedTextField(textInput, { textInput = it }, singleLine = true) },
        confirmButton = { TextButton({
            val n = textInput.trim(); dialog = ""
            if (n.isNotEmpty()) scope.launch {
                val u = withContext(Dispatchers.IO) { FolderLister.createFolder(cr, Uri.parse(cur.tree), cur.docId, n) }
                vm.logOp("مجلد جديد", "", n, cur.docId, u?.toString().orEmpty(), u != null, if (u == null) "رفض مزوّد التخزين" else null)
                if (u == null) vm.message.value = "تعذر إنشاء المجلد"
                reload++
            }
        }) { Text("إنشاء") } },
        dismissButton = { TextButton({ dialog = "" }) { Text("إلغاء") } },
    )
    if (dialog == "rename" && one != null) AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text("إعادة تسمية") },
        text = { OutlinedTextField(textInput, { textInput = it }, singleLine = true) },
        confirmButton = { TextButton({
            val n = textInput.trim(); dialog = ""
            if (n.isNotEmpty() && n != one.name) scope.launch {
                val u = withContext(Dispatchers.IO) { SafOps.rename(cr, Uri.parse(one.uri), n) }
                vm.logOp("إعادة تسمية", one.name, n, one.uri, u?.toString().orEmpty(), u != null, if (u == null) "رفض مزوّد التخزين (أو الاسم موجود)" else null)
                if (u == null) vm.message.value = "تعذرت إعادة التسمية" else selected.clear()
                reload++
            }
        }) { Text("تنفيذ") } },
        dismissButton = { TextButton({ dialog = "" }) { Text("إلغاء") } },
    )
    if (dialog == "info" && one != null) AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text(one.name) },
        text = { Text("النوع: ${if (one.isDir) "مجلد" else (one.mime ?: "غير معروف")}\nالحجم: ${if (one.isDir) "—" else fmtSize(one.size)}\nآخر تعديل: ${fmtDate(one.modified)}\nالمعرّف: ${one.docId}") },
        confirmButton = { TextButton({ dialog = "" }) { Text("إغلاق") } },
    )
    if (dialog == "delete" && sel.isNotEmpty()) AlertDialog(
        onDismissRequest = { dialog = "" }, title = { Text("حذف ${sel.size} عنصر؟") },
        text = { Text(if (sel.any { it.isDir }) "ستُحذف محتويات المجلدات المحددة أيضاً. لا تراجع من داخل التطبيق." else "لا تراجع عن الحذف من داخل التطبيق.") },
        confirmButton = { TextButton({ val list = sel.toList(); dialog = ""; doDelete(list) }) { Text("حذف") } },
        dismissButton = { TextButton({ dialog = "" }) { Text("إلغاء") } },
    )
}

@Composable
private fun ItemIcon(i: BItem, byParent: Map<String, List<FileEntry>>, sizeDp: Int) {
    if (i.isDir) {
        val pal = FolderPalette.of(byParent[i.docId].orEmpty())
        FolderIcon(pal.colors, pal.hasMore, Modifier.size((sizeDp * 1.2f).dp, sizeDp.dp))
    } else {
        val c = Color(FileType.fromExtension(i.ext).let { if (i.isHidden) FileType.HIDDEN_ARGB else it.argb })
        Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(8.dp)).background(c), contentAlignment = Alignment.Center) {
            Text((i.ext ?: "؟").take(4).uppercase(), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
