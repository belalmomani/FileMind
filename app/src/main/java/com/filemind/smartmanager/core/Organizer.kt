package com.filemind.smartmanager.core

import java.util.Calendar
import java.util.TimeZone

enum class DateSource { MODIFIED, EXIF, NAME }
enum class GroupBy { NAME, YEAR, YEAR_MONTH, EXTENSION, TYPE }

/** How month folders are named. Names, never numbers: "يناير" or the Levant calendar names "كانون الثاني". */
enum class MonthStyle(val titleAr: String, val names: List<String>) {
    STANDARD("يناير، فبراير، مارس…", listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")),
    LEVANT("كانون الثاني، شباط، آذار…", listOf("كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"));

    companion object { fun fromName(n: String?): MonthStyle = entries.firstOrNull { it.name == n } ?: STANDARD }
}

/** The word that names a kind of file inside folder names: "صور 2025", "فيديو 2025". */
fun FileType.folderWord(): String = when (this) {
    FileType.IMAGE -> "صور"
    FileType.VIDEO -> "فيديو"
    FileType.AUDIO -> "صوتيات"
    FileType.PDF -> "PDF"
    FileType.DOCUMENT -> "مستندات"
    FileType.ARCHIVE -> "أرشيفات"
    FileType.APK -> "تطبيقات"
    FileType.UNKNOWN -> "ملفات"
}

data class OrganizeRule(
    val types: Set<FileType>,
    val primary: GroupBy,
    val secondary: GroupBy? = null,
    val dateSource: DateSource = DateSource.MODIFIED,
    val destRoot: String,
    val unknownDateFolder: String? = "تاريخ غير معروف", // null => skip files with unknown date
    val monthStyle: MonthStyle = MonthStyle.STANDARD,
)

data class PlannedMove(val entry: FileEntry, val targetDir: String, val reason: String? = null)
data class OrganizePlan(val moves: List<PlannedMove>, val skipped: List<PlannedMove>, val newFolders: Set<String>)

object Organizer {
    const val NUMERIC_NAMES_FOLDER = "أسماء رقمية"

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
        GroupBy.TYPE -> e.type.folderWord()
        GroupBy.YEAR, GroupBy.YEAR_MONTH -> dateOf(e, rule.dateSource)?.let {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }
            val year = c.get(Calendar.YEAR)
            // As the main grouping the folder names WHAT it holds before the date: "صور 2025", "فيديو 2025". Under another grouping the plain year is enough.
            val head = if (g == rule.primary) "${e.type.folderWord()} $year" else "$year"
            if (g == GroupBy.YEAR) head else "$head/${rule.monthStyle.names[c.get(Calendar.MONTH)]}"
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
