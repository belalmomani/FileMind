package com.filemind.smartmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import kotlin.math.roundToInt

/**
 * Manual photo-size control in ONE tidy row: small icon, slider, large icon, value. Drag to shrink or enlarge, up to one photo per row; photos change
 * live and the size is SAVED automatically when the finger lifts.
 */
@Composable
fun ThumbSizeBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val size by vm.thumbDp.collectAsState()
    val maxDp = (LocalConfiguration.current.screenWidthDp - 8).coerceAtLeast(120)   // full row width
    var saved by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▫", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = size.coerceIn(56, maxDp).toFloat(),
            onValueChange = { saved = false; vm.setThumbDpLive(it.roundToInt()) },
            onValueChangeFinished = { vm.saveThumbDp(); saved = true },
            valueRange = 56f..maxDp.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Text("▣", style = MaterialTheme.typography.titleMedium)
        Text(
            if (saved) "✓ حُفظ" else if (size >= maxDp) "كامل" else "$size",
            Modifier.width(56.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1,
        )
    }
}
