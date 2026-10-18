package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LogicTest {
    private fun ms(y: Int, m: Int = 1) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, 15) }.timeInMillis
    private fun e(name: String, mod: Long?) = FileEntry("/d/$name", name, 10, mod)

    @Test fun renameArabicKeepsExtensionAndDetectsCollision() {
        val es = listOf(e("صورة.jpg", null), e("صورة.JPG", null))
        val p = BulkRenamer.preview(es, RenameOptions(find = "صورة", replaceWith = "رحلة"))
        assertEquals("رحلة.jpg", p[0].newName); assertTrue(p.all { it.collision }); assertFalse(BulkRenamer.canApply(p))
    }
    @Test fun renameSequenceHasNoCollision() {
        val es = listOf(e("a.jpg", null), e("b.jpg", null))
        val p = BulkRenamer.preview(es, RenameOptions(prefix = "Bilal", sequenceStart = 1))
        assertEquals(listOf("Bilal_a_001.jpg", "Bilal_b_002.jpg"), p.map { it.newName }); assertTrue(BulkRenamer.canApply(p))
    }
    @Test fun organizeByNameThenYearWithUnknownDate() {
        val es = listOf(e("Bilal_1.zip", ms(2020)), e("bilal_2.zip", ms(2025)), e("bilal_3.rar", null), e("photo.jpg", ms(2025)))
        val rule = OrganizeRule(setOf(FileType.ARCHIVE), GroupBy.NAME, GroupBy.YEAR, destRoot = "/arch")
        val plan = Organizer.plan(es, rule)
        assertEquals(setOf("/arch/bilal/2020", "/arch/bilal/2025", "/arch/تاريخ غير معروف"), plan.newFolders)
        assertEquals(3, plan.moves.size)
    }
    @Test fun unknownDateSkippedWhenUserChoosesSo() {
        val rule = OrganizeRule(setOf(FileType.PDF), GroupBy.YEAR_MONTH, destRoot = "/p", unknownDateFolder = null)
        val plan = Organizer.plan(listOf(e("a.pdf", null)), rule)
        assertTrue(plan.moves.isEmpty()); assertEquals(1, plan.skipped.size)
    }
    @Test fun exifNeverInvented() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.YEAR, dateSource = DateSource.EXIF, destRoot = "/i")
        val plan = Organizer.plan(listOf(e("a.jpg", ms(2024))), rule)
        assertEquals("/i/تاريخ غير معروف", plan.moves[0].targetDir)
    }
    @Test fun inaccessibleFilesAreSkippedNotMissing() {
        val f = e("a.zip", ms(2024)).copy(accessible = false)
        val plan = Organizer.plan(listOf(f), OrganizeRule(setOf(FileType.ARCHIVE), GroupBy.EXTENSION, destRoot = "/x"))
        assertEquals(1, plan.skipped.size)
    }
    @Test fun parserUnderstandsZip2025() {
        val q = ArabicQueryParser.parse("اعرض كل ملفات ZIP من سنة 2025")
        assertEquals(setOf("zip"), q.extensions); assertEquals(2025, q.year); assertTrue(q.isConfident)
    }
    @Test fun parserLargeImages() {
        val q = ArabicQueryParser.parse("أظهر الصور الأكبر من ٥ ميغابايت")
        assertEquals(setOf(FileType.IMAGE), q.types); assertEquals(5L * 1024 * 1024, q.minSizeBytes)
    }
    @Test fun parserSixMonths() {
        assertEquals(6, ArabicQueryParser.parse("اعرض الملفات التي لم يتم تعديلها منذ ستة أشهر").notModifiedForMonths)
    }
    @Test fun parserRefusesAmbiguous() {
        assertFalse(ArabicQueryParser.parse("افعل شيئاً غريباً").isConfident)
    }
}
