package com.filemind.smartmanager.ui

import androidx.compose.foundation.Canvas
import com.filemind.smartmanager.core.FolderSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
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

/** Folder icon coloured by how much the folder holds (red large, green medium, yellow small, black empty). [cls] == null = size not known yet: grey. */
@Composable
fun SizeFolderIcon(cls: FolderSize?, modifier: Modifier = Modifier.size(48.dp, 40.dp)) {
    val base = Color(cls?.argb ?: 0xFF9A9A94)
    Canvas(modifier) {
        val w = size.width; val h = size.height; val r = CornerRadius(h * 0.14f)
        drawRoundRect(base.copy(alpha = 0.75f), Offset(0f, 0f), Size(w * 0.42f, h * 0.35f), r)          // the tab
        drawRoundRect(base, Offset(0f, h * 0.18f), Size(w, h * 0.82f), r)                                // the body
        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(0f, h * 0.18f), Size(w, h * 0.22f), r)     // soft highlight
    }
}

/** What the folder colours mean. */
@Composable
fun FolderSizeLegend(modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(FolderSize.LARGE, FolderSize.MEDIUM, FolderSize.SMALL, FolderSize.EMPTY).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(c.argb)))
                Spacer(Modifier.width(4.dp))
                Text("${c.titleAr} (${c.rangeAr})", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
