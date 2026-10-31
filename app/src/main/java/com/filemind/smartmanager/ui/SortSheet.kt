package com.filemind.smartmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.core.SortBy
import com.filemind.smartmanager.core.SortOrder
import kotlinx.coroutines.launch

/** Orders that make sense for lists whose items have no file type (folders, comparison rows...). */
val NO_TYPE_ORDERS: List<SortOrder> = SortOrder.entries.filter { it.by != SortBy.TYPE }

/** The "Sort by" bottom sheet used by every list: the current order is ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(current: SortOrder, onPick: (SortOrder) -> Unit, onDismiss: () -> Unit, options: List<SortOrder> = SortOrder.entries) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("الترتيب حسب", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            options.forEach { o ->
                val on = o == current
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(o); onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o.label, Modifier.weight(1f), color = if (on) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    if (on) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** A screen's current sort order plus the action that opens its sheet. */
class ScreenSort(val order: SortOrder, val show: () -> Unit)

/** One call gives any list screen a REMEMBERED sort order (saved per screen) and its bottom sheet. Pair it with [SortChip]. */
@Composable
fun rememberScreenSort(vm: AppViewModel, key: String, default: SortOrder, options: List<SortOrder> = SortOrder.entries): ScreenSort {
    val saved by vm.settings.sortFor(key).collectAsState(initial = "")
    val order = SortOrder.fromName(saved, default).let { if (it in options) it else default }
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (open) SortSheet(order, { scope.launch { vm.settings.setSortFor(key, it.name) } }, { open = false }, options)
    return ScreenSort(order) { open = true }
}

@Composable
fun SortChip(s: ScreenSort, modifier: Modifier = Modifier) {
    AssistChip(s.show, { Text("⇅ " + s.order.shortLabel, maxLines = 1) }, modifier)
}
