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
 * Manual photo-size control: drag to shrink or enlarge, all the way up to ONE photo per row. Photos change live while dragging and the size is SAVED
 * automatically when the finger lifts. With [showModes] it also offers the List / Grid choice (saved too).
 */
@Composable
fun ThumbSizeBar(vm: AppViewModel, modifier: Modifier = Modifier, showModes: Boolean = false) {
    val size by vm.thumbDp.collectAsState()
    val grid by vm.photoGrid.collectAsState()
    val maxDp = (LocalConfiguration.current.screenWidthDp - 8).coerceAtLeast(120)   // full row width
    var saved by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (showModes) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!grid, { vm.setPhotoGrid(false) }, { Text("قائمة") })
            FilterChip(grid, { vm.setPhotoGrid(true) }, { Text("شبكة") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("صغيرة", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = size.coerceIn(56, maxDp).toFloat(),
                onValueChange = { saved = false; vm.setThumbDpLive(it.roundToInt()) },
                onValueChangeFinished = { vm.saveThumbDp(); saved = true },
                valueRange = 56f..maxDp.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("كبيرة", style = MaterialTheme.typography.labelSmall)
            Text(if (saved) "  حُفظ ✓" else if (size >= maxDp) "  صورة بكل صف" else "  ${size}dp", style = MaterialTheme.typography.labelSmall)
        }
    }
}
