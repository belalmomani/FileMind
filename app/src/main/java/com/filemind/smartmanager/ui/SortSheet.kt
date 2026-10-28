package com.filemind.smartmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.core.SortOrder

/** The "Sort by" bottom sheet used by every list: eight orderings, the current one is ticked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(current: SortOrder, onPick: (SortOrder) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("الترتيب حسب", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            SortOrder.entries.forEach { o ->
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
