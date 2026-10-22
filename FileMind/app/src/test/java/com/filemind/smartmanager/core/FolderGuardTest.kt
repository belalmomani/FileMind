package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class FolderGuardTest {
    @Test fun sameFolderIsBlocked() = assertTrue(FolderGuard.isSameOrInside("primary:Docs/A", "primary:Docs/A"))
    @Test fun childFolderIsBlocked() = assertTrue(FolderGuard.isSameOrInside("primary:Docs/A/B", "primary:Docs/A"))
    @Test fun siblingWithSamePrefixIsAllowed() = assertFalse(FolderGuard.isSameOrInside("primary:Docs/AB", "primary:Docs/A"))
    @Test fun parentIsAllowed() = assertFalse(FolderGuard.isSameOrInside("primary:Docs", "primary:Docs/A"))
}
