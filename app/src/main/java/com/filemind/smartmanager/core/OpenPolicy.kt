package com.filemind.smartmanager.core

/** How a tapped file is opened. ASK = show the "open with" choice every time. */
enum class OpenMode(val titleAr: String) {
    ASK("اسألني في كل مرة"),
    INTERNAL("داخل FileMind (للصور)"),
    CHOOSER("فتح بواسطة… (اختيار تطبيق)"),
    DEFAULT("التطبيق الافتراضي");

    companion object { fun fromName(n: String?): OpenMode = entries.firstOrNull { it.name == n } ?: ASK }
}

/** What can actually be done with a file. EXPLORER = show its folder in the system file explorer. */
enum class OpenAction { INTERNAL, CHOOSER, DEFAULT, EXPLORER }

object OpenPolicy {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    /** Action for a remembered mode, or null when the user must be asked. The built-in viewer only exists for images; other files fall back to the default app. */
    fun decide(mode: OpenMode, isImage: Boolean): OpenAction? = when (mode) {
        OpenMode.ASK -> null
        OpenMode.INTERNAL -> if (isImage) OpenAction.INTERNAL else OpenAction.DEFAULT
        OpenMode.CHOOSER -> OpenAction.CHOOSER
        OpenMode.DEFAULT -> OpenAction.DEFAULT
    }

    /** The mode to remember when the user ticks "remember my choice" after picking [a]. Exploring a folder is never remembered. */
    fun modeToRemember(a: OpenAction): OpenMode? = when (a) {
        OpenAction.INTERNAL -> OpenMode.INTERNAL
        OpenAction.CHOOSER -> OpenMode.CHOOSER
        OpenAction.DEFAULT -> OpenMode.DEFAULT
        OpenAction.EXPLORER -> null
    }
}
