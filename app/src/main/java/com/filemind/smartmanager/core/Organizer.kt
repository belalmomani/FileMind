package com.filemind.smartmanager.core

import java.util.Calendar
import java.util.TimeZone

enum class DateSource { MODIFIED, EXIF, NAME }
enum class GroupBy { NAME, YEAR, YEAR_MONTH, EXTENSION, TYPE }

data class OrganizeRule(
    val types: Set<FileType>,
    val primary: GroupBy,
    val secondary: GroupBy? = null,
    val dateSource: DateSource = DateSource.MODIFIED,
    val destRoot: String,
    val unknownDateFolder: String? = "تاريخ غير معروف", // null => skip files with unknown date
)

data class PlannedMove(val entry: FileEntry, val targetDir: String, val reason: String? = null)
data class OrganizePlan(val moves: List<PlannedMove>, val skipped: List<PlannedMove>, val newFolders: Set<String>)

object Organizer {
    const val NUMERIC_NAMES_FOLDER = "أسماء رقمية"
    const val MONTH_PREFIX = "شهر"

    private val yearInName = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")

    /** "Bilal_2020_01", "bilal-5" → "bilal". Shared name key for grouping similar names. */
    fun nameKey(base: String): String =
        base.lowercase().replace(Regex("[\\d\\s_\\-().]+"), " ").trim().split(' ').firstOrNull().orEmpty().ifEmpty { base.lowercase() }

    fun dateOf(e: FileEntry, s: DateSource): Long? = when (s) {
        DateSource.MODIFIED -> e.modifiedMs
        DateSource.EXIF -> e.exifDateMs
        DateSource.NAME -> yearInName.find(e.baseName)?.value?.toInt()?.let { y ->
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, 0, 1) }.timeInMillis
        }
    }

    private fun part(e: FileEntry, g: GroupBy, rule: OrganizeRule): String? = when (g) {
        GroupBy.NAME -> nameKey(e.baseName).let { k ->
            // Names with no letters (e.g. screenshots named 1785982504049) must NOT become one folder per file; share a single folder.
            if (k.any { it.isLetter() }) k else NUMERIC_NAMES_FOLDER   // TODO: smarter grouping of numeric names (by date prefix) if wanted
        }
        GroupBy.EXTENSION -> e.extension ?: "بدون_امتداد"
        GroupBy.TYPE -> e.type.name
        GroupBy.YEAR, GroupBy.YEAR_MONTH -> dateOf(e, rule.dateSource)?.let {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }
            if (g == GroupBy.YEAR) "${c.get(Calendar.YEAR)}"
            else "${c.get(Calendar.YEAR)}/$MONTH_PREFIX ${"%02d".format(c.get(Calendar.MONTH) + 1)}"   // 2025/شهر 01 … 2025/شهر 12 (sorts correctly)
        }
    }

    /** Pure planner: never touches the filesystem. Only creates groups that have real matches. */
    fun plan(entries: List<FileEntry>, rule: OrganizeRule): OrganizePlan {
        val moves = mutableListOf<PlannedMove>(); val skipped = mutableListOf<PlannedMove>()
        for (e in entries.filter { it.type in rule.types }) {
            if (!e.accessible) { skipped += PlannedMove(e, "", "تعذر الوصول"); continue }
            val a = part(e, rule.primary, rule)
            val b = rule.secondary?.let { part(e, it, rule) }
            val dateNeeded = rule.primary in setOf(GroupBy.YEAR, GroupBy.YEAR_MONTH) || rule.secondary in setOf(GroupBy.YEAR, GroupBy.YEAR_MONTH)
            val missingDate = dateNeeded && (a == null || (rule.secondary != null && b == null))
            if (missingDate) {
                if (rule.unknownDateFolder == null) skipped += PlannedMove(e, "", "تاريخ غير معروف")
                else moves += PlannedMove(e, "${rule.destRoot}/${rule.unknownDateFolder}", "تاريخ غير معروف")
                continue
            }
            moves += PlannedMove(e, listOfNotNull(rule.destRoot, a, b).joinToString("/"))
        }
        return OrganizePlan(moves, skipped, moves.map { it.targetDir }.toSet())
    }
}
