package com.filemind.smartmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.AppViewModel
import kotlin.math.roundToInt

/** Manual photo-size control: drag to shrink or enlarge; the photos change live and the size is SAVED automatically when you lift your finger. */
@Composable
fun ThumbSizeBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val size by vm.thumbDp.collectAsState()
    var saved by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("صغيرة", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = size.toFloat(),
            onValueChange = { saved = false; vm.setThumbDpLive(it.roundToInt()) },
            onValueChangeFinished = { vm.saveThumbDp(); saved = true },
            valueRange = 56f..200f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("كبيرة", style = MaterialTheme.typography.labelSmall)
        Text(if (saved) "  حُفظ ✓" else "  ${size}dp", style = MaterialTheme.typography.labelSmall)
    }
}
