package com.filemind.smartmanager.ui

import com.filemind.smartmanager.core.SortOrder
import com.filemind.smartmanager.core.SortKey
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.FileEntry
import com.filemind.smartmanager.data.ImageHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun Thumb(path: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { ImageHasher.thumbnail(ctx.contentResolver, Uri.parse(path), 160)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop) else Box(modifier.background(Color.LightGray))
}

@Composable
fun SimilarImagesScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val groups by vm.similar.collectAsState()
    val sort = rememberScreenSort(vm, "similar", SortOrder.SIZE_DESC)
    val sortedGroups = remember(groups, sort.order) {
        val cmp = sort.order.comparator()
        fun key(g: List<FileEntry>) = SortKey(g.first().name, g.first().size, g.maxOfOrNull { it.modifiedMs ?: 0L }?.takeIf { it > 0 }, g.first().extension ?: "")
        groups.orEmpty().sortedWith { a, b -> cmp.compare(key(a), key(b)) }
    }
    val prog by vm.similarProgress.collectAsState()
    val skipped by vm.similarSkipped.collectAsState()
    val sel = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf(false) }
    val delete = rememberDeleter(vm) { sel.clear(); vm.similar.value = null }
    val removesAll = groups?.any { g -> g.all { it.path in sel } } == true
    val selected = groups?.flatten()?.filter { it.path in sel }.orEmpty()

    fun open(e: FileEntry) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.extension ?: "") ?: "image/*"
        vm.recordOpen(e.path); try { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.path), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (ex: Exception) { vm.message.value = "لا يوجد تطبيق لعرض الصورة" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("رجوع") }; Spacer(Modifier.weight(1f))
            SortChip(sort); Spacer(Modifier.width(8.dp))
            Button({ sel.clear(); vm.scanSimilar() }, enabled = prog == null) { Text(prog ?: "فحص الصور المتشابهة") }
        }
        Text("الصور هنا متشابهة بصرياً وليست بالضرورة متطابقة (بصمة إدراكية dHash). راجع كل مجموعة بنفسك؛ لا حذف تلقائياً.", style = MaterialTheme.typography.bodySmall)
        if (groups == null && prog == null) Text("اضغط «فحص الصور المتشابهة». يحلل التطبيق نسخاً مصغّرة محلياً فقط.")
        if (groups?.isEmpty() == true) Text("لم يُعثر على صور متشابهة." + if (skipped > 0) " (تعذر تحليل $skipped صورة)" else "")
        else if (groups != null && skipped > 0) Text("تعذر تحليل $skipped صورة (صيغة غير مدعومة).", style = MaterialTheme.typography.bodySmall)
        if (removesAll) Text("أبقِ صورة واحدة على الأقل في كل مجموعة.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(sortedGroups) { i, g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("مجموعة ${i + 1}: ${g.size} صور", style = MaterialTheme.typography.titleSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(g, key = { it.path }) { e ->
                                Column(Modifier.width(130.dp)) {
                                    Thumb(e.path, Modifier.size(130.dp).clickable { open(e) })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(e.path in sel, { if (it) sel.add(e.path) else sel.remove(e.path) })
                                        Text(fmtSize(e.size), style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(e.name, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                    Text(fmtDate(e.modifiedMs), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                    Text(e.parent, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (sel.isNotEmpty()) Button({ confirm = true }, enabled = !removesAll) { Text("حذف المحدد (${sel.size})") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("حذف ${selected.size} صورة؟") }, text = { Text("ستبقى صورة واحدة على الأقل من كل مجموعة. لا تراجع من داخل التطبيق.") },
        confirmButton = { TextButton({ confirm = false; delete(selected) }) { Text("حذف") } },
        dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } },
    )
}
