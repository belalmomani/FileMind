package com.filemind.smartmanager.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

enum class Combine { AND, OR }
enum class SortBy { NAME, SIZE, MODIFIED }

data class FileFilter(
    val query: String = "",
    val nameStartsWith: String = "",
    val nameEndsWith: String = "",
    val types: Set<FileType> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedBeforeMs: Long? = null,
    val modifiedAfterMs: Long? = null,
    val year: Int? = null,
    val folderContains: String = "",
    val onlyNoExtension: Boolean = false,
    val includeHidden: Boolean = true,   // hard exclusion when false (not part of AND/OR)
    val combine: Combine = Combine.AND,
    val sortBy: SortBy = SortBy.MODIFIED,
    val descending: Boolean = true,
) {
    private fun checks(e: FileEntry): List<Boolean> = buildList {
        if (query.isNotBlank()) add(e.name.contains(query.trim(), ignoreCase = true))
        if (nameStartsWith.isNotBlank()) add(e.name.startsWith(nameStartsWith, ignoreCase = true))
        if (nameEndsWith.isNotBlank()) add(e.baseName.endsWith(nameEndsWith, ignoreCase = true))
        if (types.isNotEmpty()) add(e.type in types)
        if (extensions.isNotEmpty()) add(e.extension in extensions)
        minSize?.let { add(e.size >= it) }
        maxSize?.let { add(e.size <= it) }
        modifiedBeforeMs?.let { add(e.modifiedMs != null && e.modifiedMs < it) }   // unknown date never matches
        modifiedAfterMs?.let { add(e.modifiedMs != null && e.modifiedMs > it) }
        year?.let { y -> add(e.modifiedMs != null && Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = e.modifiedMs }.get(Calendar.YEAR) == y) }
        if (folderContains.isNotBlank()) add(e.parent.contains(folderContains.trim(), ignoreCase = true))
        if (onlyNoExtension) add(e.extension == null)
    }

    fun matches(e: FileEntry): Boolean {
        if (!includeHidden && e.isHidden) return false
        val c = checks(e)
        if (c.isEmpty()) return true
        return if (combine == Combine.AND) c.all { it } else c.any { it }
    }

    fun apply(list: List<FileEntry>): List<FileEntry> {
        val f = list.filter(::matches)
        val cmp: Comparator<FileEntry> = when (sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.MODIFIED -> compareBy { it.modifiedMs ?: Long.MIN_VALUE }
        }
        return f.sortedWith(if (descending) cmp.reversed() else cmp)
    }

    /** Human-readable summary of active conditions (shown before any bulk action). */
    fun describe(): List<String> = buildList {
        if (query.isNotBlank()) add("الاسم يحتوي «$query»")
        if (nameStartsWith.isNotBlank()) add("يبدأ بـ «$nameStartsWith»")
        if (nameEndsWith.isNotBlank()) add("ينتهي بـ «$nameEndsWith»")
        if (types.isNotEmpty()) add("النوع: " + types.joinToString("، ") { it.name })
        if (extensions.isNotEmpty()) add("الامتداد: " + extensions.joinToString("، "))
        minSize?.let { add("الحجم ≥ ${it / (1024 * 1024)} MB") }
        maxSize?.let { add("الحجم ≤ ${it / (1024 * 1024)} MB") }
        if (modifiedBeforeMs != null) add("معدّل قبل تاريخ محدد")
        if (year != null) add("سنة التعديل $year")
        if (folderContains.isNotBlank()) add("المجلد يحتوي «$folderContains»")
        if (onlyNoExtension) add("بدون امتداد")
        if (!includeHidden) add("إخفاء الملفات المخفية")
    }

    fun toJson(): String = JSONObject().apply {
        put("query", query); put("starts", nameStartsWith); put("ends", nameEndsWith)
        put("types", JSONArray(types.map { it.name })); put("exts", JSONArray(extensions.toList()))
        minSize?.let { put("min", it) }; maxSize?.let { put("max", it) }
        modifiedBeforeMs?.let { put("before", it) }; modifiedAfterMs?.let { put("after", it) }
        year?.let { put("year", it) }
        put("folder", folderContains); put("noext", onlyNoExtension); put("hidden", includeHidden)
        put("combine", combine.name); put("sort", sortBy.name); put("desc", descending)
    }.toString()

    companion object {
        fun fromJson(s: String): FileFilter = try {
            val o = JSONObject(s)
            fun strs(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            FileFilter(
                query = o.optString("query"), nameStartsWith = o.optString("starts"), nameEndsWith = o.optString("ends"),
                types = strs(o.optJSONArray("types") ?: JSONArray()).mapNotNull { runCatching { FileType.valueOf(it) }.getOrNull() }.toSet(),
                extensions = strs(o.optJSONArray("exts") ?: JSONArray()).toSet(),
                minSize = if (o.has("min")) o.getLong("min") else null, maxSize = if (o.has("max")) o.getLong("max") else null,
                modifiedBeforeMs = if (o.has("before")) o.getLong("before") else null,
                modifiedAfterMs = if (o.has("after")) o.getLong("after") else null,
                year = if (o.has("year")) o.getInt("year") else null,
                folderContains = o.optString("folder"), onlyNoExtension = o.optBoolean("noext"), includeHidden = o.optBoolean("hidden", true),
                combine = runCatching { Combine.valueOf(o.optString("combine")) }.getOrDefault(Combine.AND),
                sortBy = runCatching { SortBy.valueOf(o.optString("sort")) }.getOrDefault(SortBy.MODIFIED),
                descending = o.optBoolean("desc", true),
            )
        } catch (e: Exception) { FileFilter() }
    }
}

const val MB = 1024L * 1024L
const val DAY_MS = 24L * 60 * 60 * 1000

fun ParsedQuery.toFileFilter(nowMs: Long): FileFilter = FileFilter(
    types = types, extensions = extensions, minSize = minSizeBytes, year = year, folderContains = folderHint.orEmpty(),
    modifiedBeforeMs = notModifiedForMonths?.let { nowMs - it * 30L * DAY_MS },
    sortBy = if (minSizeBytes != null) SortBy.SIZE else SortBy.MODIFIED,
)

/** Real, content-derived categories. Each is a virtual view over the same files — nothing is copied. */
enum class Category(val titleAr: String, val argb: Long, val test: (FileEntry, Long) -> Boolean) {
    IMAGES("الصور", FileType.IMAGE.argb, { e, _ -> e.type == FileType.IMAGE }),
    SCREENSHOTS("لقطات الشاشة", FileType.IMAGE.argb, { e, _ -> e.type == FileType.IMAGE && (e.name.contains("screenshot", true) || e.name.contains("لقطة") || e.parent.contains("screenshot", true)) }),
    BIG_IMAGES("الصور الكبيرة", FileType.IMAGE.argb, { e, _ -> e.type == FileType.IMAGE && e.size >= 5 * MB }),
    VIDEOS("الفيديوهات", FileType.VIDEO.argb, { e, _ -> e.type == FileType.VIDEO }),
    AUDIO("الصوتيات", FileType.AUDIO.argb, { e, _ -> e.type == FileType.AUDIO }),
    PDF("PDF والكتب", FileType.PDF.argb, { e, _ -> e.type == FileType.PDF }),
    DOCS("المستندات", FileType.DOCUMENT.argb, { e, _ -> e.type == FileType.DOCUMENT }),
    ARCHIVES("الملفات المضغوطة", FileType.ARCHIVE.argb, { e, _ -> e.type == FileType.ARCHIVE }),
    APKS("ملفات APK", FileType.APK.argb, { e, _ -> e.type == FileType.APK }),
    LARGE("الملفات الكبيرة (100MB+)", 0xFF8C7A6B, { e, _ -> e.size >= 100 * MB }),
    HIDDEN("الملفات المخفية", FileType.HIDDEN_ARGB, { e, _ -> e.isHidden }),
    UNKNOWN("غير معروفة / بلا امتداد", FileType.UNKNOWN.argb, { e, _ -> e.type == FileType.UNKNOWN }),
    RECENT("الحديثة (7 أيام)", 0xFF5E8C6A, { e, now -> e.modifiedMs != null && now - e.modifiedMs <= 7 * DAY_MS }),
    STALE("تحتاج مراجعة — لم تُعدَّل منذ 6 أشهر", 0xFFB58B6B, { e, now -> e.modifiedMs != null && now - e.modifiedMs > 183 * DAY_MS });
    // TODO: DUPLICATES category (needs hashing pass via DuplicateFinder over SAF streams).
}

/** Top-3 type colors of a folder's real contents + whether more types exist. */
object FolderPalette {
    data class Result(val colors: List<Long>, val hasMore: Boolean)
    fun of(entries: Collection<FileEntry>): Result {
        val ranked = entries.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
        return Result(ranked.take(3).map { it.key.argb }, ranked.size > 3)
    }
}
