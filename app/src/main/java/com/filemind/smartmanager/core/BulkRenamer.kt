package com.filemind.smartmanager.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RenameOptions(
    val find: String? = null, val replaceWith: String = "",
    val prefix: String = "", val suffix: String = "",
    val sequenceStart: Int? = null, val sequenceDigits: Int = 3,
    val datePattern: String? = null, val useExif: Boolean = false,
    val trimSpaces: Boolean = false, val lowercase: Boolean = false,
)

data class RenamePreview(val entry: FileEntry, val newName: String, val collision: Boolean)

object BulkRenamer {
    /** Pure preview. Extension is always preserved. Collisions are flagged, never auto-resolved. */
    fun preview(entries: List<FileEntry>, o: RenameOptions, existingNamesInDir: Set<String> = emptySet()): List<RenamePreview> {
        val names = entries.mapIndexed { i, e ->
            var base = e.baseName
            if (!o.find.isNullOrEmpty()) base = base.replace(o.find, o.replaceWith)
            if (o.trimSpaces) base = base.trim().replace(Regex("\\s+"), "_")
            if (o.lowercase) base = base.lowercase()
            val parts = mutableListOf<String>()
            if (o.datePattern != null) {
                val ms = if (o.useExif) e.exifDateMs else e.modifiedMs
                if (ms != null) parts += SimpleDateFormat(o.datePattern, Locale.ROOT).format(Date(ms))
            }
            if (o.prefix.isNotEmpty()) parts += o.prefix.removeSuffix("_")
            parts += base
            if (o.sequenceStart != null) parts += (o.sequenceStart + i).toString().padStart(o.sequenceDigits, '0')
            var n = parts.joinToString("_")
            n += o.suffix
            if (e.extension != null) n += "." + e.name.substringAfterLast('.')
            n
        }
        val counts = names.groupingBy { it.lowercase() }.eachCount()
        val own = entries.map { it.name.lowercase() }.toSet()
        return entries.zip(names).map { (e, n) ->
            val clash = counts[n.lowercase()]!! > 1 || (n.lowercase() in existingNamesInDir.map { it.lowercase() } && n.lowercase() !in own)
            RenamePreview(e, n, clash)
        }
    }
    fun canApply(p: List<RenamePreview>) = p.none { it.collision }
}
