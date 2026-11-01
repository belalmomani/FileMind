package com.filemind.smartmanager.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.OpenRequest
import com.filemind.smartmanager.core.OpenAction
import com.filemind.smartmanager.data.ImageHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hosts the "open with" sheet and FileMind's built-in image viewer. Placed once in MainActivity so every screen can open files the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenHost(vm: AppViewModel) {
    val req by vm.openRequest.collectAsState()
    val viewing by vm.viewerImage.collectAsState()
    req?.let { r ->
        var keep by remember(r.path) { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { vm.openRequest.value = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("فتح: ${r.name}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                @Composable fun option(glyph: String, label: String, action: OpenAction) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.perform(action, r, keep) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text(glyph, Modifier.width(36.dp)); Text(label) }
                }
                if (r.isImage) option("🖼️", "عرض داخل FileMind", OpenAction.INTERNAL)
                option("📂", "فتح بواسطة… (اختيار تطبيق)", OpenAction.CHOOSER)
                option("▶️", "فتح بالتطبيق الافتراضي", OpenAction.DEFAULT)
                option("🗂️", "إظهار في مستكشف النظام", OpenAction.EXPLORER)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().clickable { keep = !keep }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it })
                    Text("تذكّر اختياري (يمكن تغييره من الإعدادات)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    viewing?.let { r -> ImageViewer(r, onClose = { vm.viewerImage.value = null }, onOpenWith = { vm.viewerImage.value = null; vm.perform(OpenAction.CHOOSER, r, false) }) }
}

/** Full-screen image viewer: pinch to zoom, drag to move. For more options use "open with". */
@Composable
private fun ImageViewer(r: OpenRequest, onClose: () -> Unit, onOpenWith: () -> Unit) {
    val cr = LocalContext.current.contentResolver
    val bmp by produceState<ImageBitmap?>(null, r.path) { value = withContext(Dispatchers.IO) { ImageHasher.thumbnail(cr, Uri.parse(r.path), 1024)?.asImageBitmap() } }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val b = bmp
            if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else Image(
                b, null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose) { Text("✕ إغلاق", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onOpenWith) { Text("فتح بواسطة…", color = Color.White) }
            }
            Text(r.name, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.White, maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
    }
}
