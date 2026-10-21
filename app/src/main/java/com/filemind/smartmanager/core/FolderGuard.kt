package com.filemind.smartmanager.core

object FolderGuard {
    /** True when a destination folder is the source folder itself or lies inside it (document-id paths like "primary:Docs/A/B"). */
    fun isSameOrInside(destDocId: String, srcDocId: String): Boolean =
        destDocId == srcDocId || destDocId.startsWith(srcDocId.trimEnd('/') + "/")
}
