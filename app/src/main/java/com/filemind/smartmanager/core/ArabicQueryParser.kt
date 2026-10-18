package com.filemind.smartmanager.core

data class ParsedQuery(
    val types: Set<FileType> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val minSizeBytes: Long? = null,
    val year: Int? = null,
    val notModifiedForMonths: Int? = null,
    val folderHint: String? = null,
    val wantsDuplicates: Boolean = false,
    val wantsSizeSummary: Boolean = false,
    val unrecognized: List<String> = emptyList(),
) {
    /** Only act when something was understood and nothing was left unexplained. */
    val isConfident get() = unrecognized.isEmpty() &&
        (types.isNotEmpty() || extensions.isNotEmpty() || minSizeBytes != null || year != null ||
            notModifiedForMonths != null || wantsDuplicates || folderHint != null)
}

/** Local, deterministic rules. Shows what it understood; never claims to understand everything. */
object ArabicQueryParser {
    private val digitsMap = "٠١٢٣٤٥٦٧٨٩".withIndex().associate { it.value to ('0' + it.index) }
    private val words = mapOf("واحد" to 1, "اثنين" to 2, "ثلاثة" to 3, "اربعة" to 4, "أربعة" to 4, "خمسة" to 5, "ستة" to 6, "سبعة" to 7, "ثمانية" to 8, "تسعة" to 9, "عشرة" to 10, "اثني عشر" to 12)
    private val typeWords = mapOf(
        "صور" to FileType.IMAGE, "صورة" to FileType.IMAGE, "الصور" to FileType.IMAGE,
        "فيديو" to FileType.VIDEO, "فيديوهات" to FileType.VIDEO, "الفيديوهات" to FileType.VIDEO,
        "صوتيات" to FileType.AUDIO, "موسيقى" to FileType.AUDIO,
        "الأرشيفات" to FileType.ARCHIVE, "أرشيفات" to FileType.ARCHIVE, "المضغوطة" to FileType.ARCHIVE,
        "مستندات" to FileType.DOCUMENT,
    )
    private val extWords = setOf("zip", "rar", "7z", "pdf", "apk")
    private val stop = setOf("اعرض", "أظهر", "اظهر", "كل", "ملفات", "الملفات", "من", "في", "التي", "التى", "الموجودة", "مجلد", "عندي", "كم", "مساحة", "أين", "اين", "سنة", "منذ", "لم", "يتم", "تعديلها", "أكبر", "اكبر", "ميغابايت", "ميجابايت", "أشهر", "اشهر", "شهر")

    fun normalize(s: String): String = s.map { digitsMap[it] ?: it }.joinToString("")
        .replace(Regex("[\u064B-\u065F\u0640]"), "").replace('؟', ' ').replace('،', ' ').trim()

    fun parse(raw: String): ParsedQuery {
        val s = normalize(raw)
        val tokens = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var q = ParsedQuery()
        val used = mutableSetOf<String>()
        tokens.forEach { t0 ->
            val t = t0.lowercase()
            when {
                t in extWords -> { q = q.copy(extensions = q.extensions + t); used += t0 }
                typeWords.containsKey(t0) -> { q = q.copy(types = q.types + typeWords.getValue(t0)); used += t0 }
                t0 == "المكررة" || t0 == "المتشابهة" || t0 == "مكررة" -> { q = q.copy(wantsDuplicates = true); used += t0 }
            }
        }
        Regex("(?:أكبر|اكبر) من\\s+(\\d+)\\s*(?:ميغابايت|ميجابايت|mb)", RegexOption.IGNORE_CASE).find(s)?.let {
            q = q.copy(minSizeBytes = it.groupValues[1].toLong() * 1024 * 1024); used += it.value.split(' ')
        }
        Regex("سنة\\s+((?:19|20)\\d{2})").find(s)?.let { q = q.copy(year = it.groupValues[1].toInt()); used += it.value.split(' ') }
        Regex("منذ\\s+(\\S+)\\s+(?:أشهر|اشهر|شهر)").find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1]]
            if (n != null) { q = q.copy(notModifiedForMonths = n); used += m.value.split(' ') }
        }
        Regex("مجلد\\s+(\\S+)").find(s)?.let { q = q.copy(folderHint = it.groupValues[1]); used += it.value.split(' ') }
        if (s.startsWith("كم مساحة")) q = q.copy(wantsSizeSummary = true)
        val leftover = tokens.filter { it !in used && it !in stop && it.toIntOrNull() == null && it.lowercase() !in extWords }
        return q.copy(unrecognized = leftover)
    }
}
