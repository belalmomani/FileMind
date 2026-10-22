package com.filemind.smartmanager.core

/** Single source of truth for type → color (ARGB). Used by icons, folders, charts, timeline. */
enum class FileType(val argb: Long, val extensions: Set<String>) {
    IMAGE(0xFFA79BD6, setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")),
    VIDEO(0xFF4FA3B8, setOf("mp4", "mkv", "avi", "mov", "3gp", "webm")),
    AUDIO(0xFF7FA38A, setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")),
    PDF(0xFFB58B6B, setOf("pdf", "epub")),
    DOCUMENT(0xFFC9A98B, setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "csv")),
    ARCHIVE(0xFFD4B26A, setOf("zip", "rar", "7z")),
    APK(0xFF7C93A8, setOf("apk", "xapk")),
    UNKNOWN(0xFF9A9A94, emptySet());

    companion object {
        fun fromExtension(ext: String?): FileType {
            val e = ext?.lowercase().orEmpty()
            return entries.firstOrNull { e in it.extensions } ?: UNKNOWN
        }
        const val HIDDEN_ARGB = 0xFF9A93A8
    }
}

data class FileEntry(
    val path: String,
    val name: String,
    val size: Long,
    val modifiedMs: Long?,          // null = unknown
    val exifDateMs: Long? = null,   // null = no EXIF; never invented
    val accessible: Boolean = true,
    val parent: String = "",      // folder label (document id / relative path)
) {
    val extension: String? get() = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
    val baseName: String get() = if (extension == null) name else name.substringBeforeLast('.')
    val type: FileType get() = FileType.fromExtension(extension)
    val isHidden: Boolean get() = name.startsWith(".")
}
