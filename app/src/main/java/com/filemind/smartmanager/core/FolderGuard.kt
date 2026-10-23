package com.filemind.smartmanager.core

object FolderGuard {
    /**
     * True when a destination folder is the source folder itself or lies inside it (document-id paths like "primary:Docs/A/B").
     * A storage ROOT has the id "primary:" (ends with ':'), so anything on that same volume is inside it; a different volume never is.
     */
    fun isSameOrInside(destDocId: String, srcDocId: String): Boolean {
        if (destDocId == srcDocId) return true
        val base = srcDocId.trimEnd('/')
        return if (base.endsWith(":")) destDocId.startsWith(base) else destDocId.startsWith("$base/")
    }
}
