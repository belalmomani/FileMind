package com.filemind.smartmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/** Folder shape whose body is split into 1–3 type colors computed from the folder's REAL contents. */
@Composable
fun FolderIcon(colors: List<Long>, hasMore: Boolean, modifier: Modifier = Modifier.size(48.dp, 40.dp)) {
    val cs = colors.ifEmpty { listOf(0xFF9A9A94) }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val r = CornerRadius(h * 0.14f)
        drawRoundRect(Color(cs.first()).copy(alpha = 0.75f), Offset(0f, 0f), Size(w * 0.42f, h * 0.35f), r)
        val body = Path().apply { addRoundRect(RoundRect(Rect(0f, h * 0.18f, w, h), r)) }
        clipPath(body) {
            val seg = w / cs.size
            cs.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * seg, h * 0.18f), Size(seg + 1f, h * 0.82f)) }
        }
        if (hasMore) drawCircle(Color.White, h * 0.09f, Offset(w * 0.88f, h * 0.34f))
    }
}
