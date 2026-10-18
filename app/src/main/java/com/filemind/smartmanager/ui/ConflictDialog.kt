package com.filemind.smartmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.filemind.smartmanager.core.ConflictDecision
import com.filemind.smartmanager.core.ConflictRelation
import com.filemind.smartmanager.data.UiConflict

@Composable
fun ConflictDialog(c: UiConflict, onAnswer: (ConflictDecision, Boolean) -> Unit) {
    var all by remember(c) { mutableStateOf(false) }
    val verdict = when (c.relation) {
        ConflictRelation.IDENTICAL_CONTENT -> "المحتوى متطابق تماماً (تم التحقق ببصمة SHA-256)."
        ConflictRelation.DIFFERENT_CONTENT -> "الاسم متشابه لكن المحتوى مختلف."
        ConflictRelation.UNREADABLE -> "تعذرت مقارنة المحتوى."
    }
    AlertDialog(
        onDismissRequest = { onAnswer(ConflictDecision.CANCEL, false) },
        title = { Text("ملف بنفس الاسم موجود: ${c.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(verdict, style = MaterialTheme.typography.titleSmall)
                Text("القديم (في الوجهة): ${fmtSize(c.oldSize)} — ${fmtDate(c.oldModified)}\n${c.oldLocation}")
                Text("الجديد (المصدر): ${fmtSize(c.newSize)} — ${fmtDate(c.newModified)}\n${c.newLocation}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(all, { all = it }); Text("تطبيق على كل التعارضات في هذه العملية")
                }
                // TODO: side-by-side content comparison view (images/text).
            }
        },
        confirmButton = { TextButton({ onAnswer(ConflictDecision.REPLACE, all) }) { Text("استبدال القديم") } },
        dismissButton = {
            Row {
                TextButton({ onAnswer(ConflictDecision.KEEP_EXISTING, all) }) { Text("احتفاظ بالقديم") }
                TextButton({ onAnswer(ConflictDecision.CANCEL, false) }) { Text("إلغاء") }
            }
        },
    )
}
