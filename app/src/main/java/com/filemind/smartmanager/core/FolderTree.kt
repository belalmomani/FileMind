package com.filemind.smartmanager.core

/** Folder colour = how much it holds: red large, green medium, yellow small, black empty. */
enum class FolderSize(val titleAr: String, val rangeAr: String, val argb: Long) {
    EMPTY("فارغ", "بلا ملفات", 0xFF212121),
    SMALL("صغير", "أقل من 50 م.ب", 0xFFFBC02D),
    MEDIUM("متوسط", "50 م.ب – 1 ج.ب", 0xFF43A047),
    LARGE("كبير", "1 ج.ب فأكثر", 0xFFE53935),
}

/** A folder of the real storage tree rebuilt from the indexed files. Totals include everything below it. */
class FolderNode(val name: String, val path: String) {
    val children = LinkedHashMap<String, FolderNode>()
    val files = ArrayList<FileEntry>()          // files directly inside this folder
    var totalFiles = 0
    var totalSize = 0L
    var newestMs: Long? = null

    val size: FolderSize get() = FolderTree.sizeClass(totalFiles, totalSize)

    internal fun add(f: FileEntry) {
        totalFiles++; totalSize += f.size
        val d = f.exifDateMs ?: f.modifiedMs
        val n = newestMs
        if (d != null && (n == null || d > n)) newestMs = d
    }
}

object FolderTree {
    const val SMALL_MAX = 50L * 1024 * 1024
    const val MEDIUM_MAX = 1024L * 1024 * 1024

    fun sizeClass(count: Int, bytes: Long): FolderSize = when {
        count == 0 || bytes <= 0L -> FolderSize.EMPTY
        bytes < SMALL_MAX -> FolderSize.SMALL
        bytes < MEDIUM_MAX -> FolderSize.MEDIUM
        else -> FolderSize.LARGE
    }

    /** "primary:Download/x/", "/Download/x" and "Download/x/" are all "Download/x". Other volumes: "1234-ABCD:Pics" -> "1234-ABCD/Pics". */
    fun normalize(parent: String): String {
        var p = parent.replace('\\', '/').replace(':', '/').replace(Regex("/+"), "/").trim('/')
        if (p == "primary" || p == "storage/emulated/0") p = ""
        return p.removePrefix("primary/").removePrefix("storage/emulated/0/")
    }

    fun build(files: List<FileEntry>): FolderNode {
        val root = FolderNode("", "")
        for (f in files) {
            root.add(f)
            var node = root; var path = ""
            for (seg in normalize(f.parent).split('/').filter { it.isNotEmpty() }) {
                path = if (path.isEmpty()) seg else "$path/$seg"
                node = node.children.getOrPut(seg) { FolderNode(seg, path) }
                node.add(f)
            }
            node.files += f
        }
        return root
    }

    fun find(root: FolderNode, path: String): FolderNode? =
        path.split('/').filter { it.isNotEmpty() }.fold(root as FolderNode?) { n, seg -> n?.children?.get(seg) }
}
