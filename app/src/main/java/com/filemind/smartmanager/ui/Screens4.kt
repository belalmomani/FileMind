package com.filemind.smartmanager.ui

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
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(vm: AppViewModel, openMonth: (String, (FileEntry, Long) -> Boolean) -> Unit, onBack: () -> Unit) {
    val files by vm.files.collectAsState()
    var source by remember { mutableStateOf(DateSource.MODIFIED) }
    val result = remember(files, source) { Timeline.build(files, source) }
    val byYear = remember(result) { result.months.groupBy { it.year } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            TextButton(onBack) { Text("رجوع") }
            Text("آلة الزمن", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(source == DateSource.MODIFIED, { source = DateSource.MODIFIED }, { Text("تاريخ التعديل") })
                FilterChip(source == DateSource.EXIF, { source = DateSource.EXIF }, { Text("تاريخ الالتقاط (EXIF)") })
            }
            Text(if (source == DateSource.EXIF) "الملفات بلا تاريخ التقاط تظهر في «تاريخ غير معروف» ولا يُخترع لها تاريخ." else "تاريخ التعديل ليس بالضرورة تاريخ إنشاء المحتوى.", style = MaterialTheme.typography.bodySmall)
            if (result.months.isEmpty() && result.unknown.isEmpty()) Text("لا توجد ملفات مفحوصة بعد.")
        }
        byYear.forEach { (year, ms) ->
            item { Text("$year — ${ms.sumOf { it.count }} ملف", style = MaterialTheme.typography.titleMedium) }
            items(ms, key = { "${it.year}-${it.month}" }) { g ->
                val (from, to) = Timeline.monthRange(g.year, g.month)
                val title = "%d-%02d".format(g.year, g.month)
                Row(Modifier.fillMaxWidth().clickable {
                    openMonth(title) { e, _ -> Organizer.dateOf(e, source)?.let { it >= from && it < to } == true }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { g.colors.forEach { c -> Box(Modifier.size(10.dp).background(Color(c))) } }
                    Text(title, Modifier.weight(1f)); Text("${g.count} ملف · ${fmtSize(g.size)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (result.unknown.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().clickable { openMonth("تاريخ غير معروف") { e, _ -> Organizer.dateOf(e, source) == null } }.padding(vertical = 8.dp)) {
                Text("تاريخ غير معروف", Modifier.weight(1f)); Text("${result.unknown.size} ملف", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
