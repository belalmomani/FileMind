package com.filemind.smartmanager

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filemind.smartmanager.ads.AdsManager
import com.filemind.smartmanager.core.*
import com.filemind.smartmanager.data.*
import com.filemind.smartmanager.data.db.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsRepository(app)
    private val db = AppDatabase.get(app)

    val files = MutableStateFlow<List<FileEntry>>(emptyList())
    val scanning = MutableStateFlow(false)
    val failedFolders = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val conflict = MutableStateFlow<UiConflict?>(null)
    val logs = db.logs().all().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val roots = settings.roots.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // Log retention: delete old log rows only (never files).
        viewModelScope.launch {
            val d = settings.logRetentionDays.first()
            if (d > 0) db.logs().deleteOlderThan(System.currentTimeMillis() - d * 86_400_000L)
        }
    }

    private var pending: CompletableDeferred<Pair<ConflictDecision, Boolean>>? = null
    private var applyAll: ConflictDecision? = null

    fun rescan() = viewModelScope.launch {
        scanning.value = true
        val (all, failed) = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val acc = ArrayList<FileEntry>(); val fail = ArrayList<String>()
            MediaScanner(cr).scan().let { acc += it.files; fail += it.failedFolders }
            roots.value.forEach { r -> SafScanner(cr).scan(Uri.parse(r)).let { acc += it.files; fail += it.failedFolders } }
            dedupeAcrossSources(acc) to fail
        }
        files.value = all; failedFolders.value = failed; scanning.value = false
    }

    fun addRoot(uri: Uri) = viewModelScope.launch { settings.addRoot(uri.toString()); rescan() }
    fun removeRoot(uri: String) = viewModelScope.launch { settings.removeRoot(uri); rescan() }

    suspend fun loadFilter(key: String): FileFilter = db.filters().get(key)?.let { FileFilter.fromJson(it.json) } ?: FileFilter()
    fun saveFilter(key: String, f: FileFilter) = viewModelScope.launch { db.filters().put(SavedFilterEntity(key, key, f.toJson())) }
    fun resetFilter(key: String) = viewModelScope.launch { db.filters().reset(key) }

    fun answerConflict(d: ConflictDecision, all: Boolean) { pending?.complete(d to all) }

    private suspend fun ask(c: UiConflict): ConflictDecision {
        applyAll?.let { return it }
        val d = CompletableDeferred<Pair<ConflictDecision, Boolean>>()
        pending = d; conflict.value = c
        val (dec, all) = d.await()
        conflict.value = null
        if (all) applyAll = dec
        return dec
    }

    fun transfer(sources: List<FileEntry>, destTree: Uri, move: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        applyAll = null; AdsManager.fileOperationInProgress = true
        message.value = "جارٍ التنفيذ…"
        val results = withContext(Dispatchers.IO) { SafTransfer(getApplication(), ::ask).run(sources, destTree, move) }
        val now = System.currentTimeMillis()
        results.forEach { r ->
            db.logs().insert(OperationLog(type = if (move) "نقل" else "نسخ", timeMs = now, oldName = r.entry.name, newName = r.entry.name,
                oldPath = r.entry.path, newPath = r.newPath, size = r.entry.size, status = r.status.name, reason = r.reason))
        }
        AdsManager.fileOperationInProgress = false
        val ok = results.count { it.status == TransferStatus.DONE }
        message.value = "اكتمل: $ok ناجح، ${results.count { it.status == TransferStatus.SKIPPED }} متجاوَز، ${results.count { it.status == TransferStatus.FAILED }} فشل، ${results.count { it.status == TransferStatus.CANCELLED }} ملغى"
        onDone(); rescan()
    }

    /** Logs the outcome of a delete performed by the UI layer (MediaStore delete request or SAF delete). */
    fun logDelete(entries: List<FileEntry>, ok: Boolean, reason: String? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        entries.forEach { e -> db.logs().insert(OperationLog(type = "حذف", timeMs = now, oldName = e.name, newName = "", oldPath = e.path, newPath = "", size = e.size, status = if (ok) "DONE" else "FAILED", reason = reason)) }
        if (ok) rescan()
    }

    fun clearLog() = viewModelScope.launch { db.logs().clear() }

    /** The same physical file can appear via MediaStore AND a granted folder; keep the folder entry only. */
    private fun dedupeAcrossSources(all: List<FileEntry>): List<FileEntry> {
        fun isMedia(e: FileEntry) = Uri.parse(e.path).authority == "media"
        fun key(e: FileEntry) = Triple(e.name, e.size, (e.modifiedMs ?: -1L) / 1000)
        val saf = all.filter { !isMedia(it) }.map(::key).toSet()
        return all.filter { !isMedia(it) || key(it) !in saf }
    }

    private suspend fun logResults(type: String, results: List<OpResult>) {
        val now = System.currentTimeMillis()
        results.forEach { r ->
            db.logs().insert(OperationLog(type = type, timeMs = now, oldName = r.entry.name, newName = r.entry.name,
                oldPath = r.entry.path, newPath = r.newPath, size = r.entry.size, status = r.status.name, reason = r.reason))
        }
        message.value = "اكتمل: ${results.count { it.status == TransferStatus.DONE }} ناجح، ${results.count { it.status == TransferStatus.SKIPPED }} متجاوَز، ${results.count { it.status == TransferStatus.FAILED }} فشل، ${results.count { it.status == TransferStatus.CANCELLED }} ملغى"
    }

    // ---- Duplicates (exact content only; never deletes by itself) ----
    val duplicates = MutableStateFlow<List<List<FileEntry>>?>(null)
    val dupProgress = MutableStateFlow<String?>(null)

    fun scanDuplicates() = viewModelScope.launch {
        dupProgress.value = "جارٍ الفحص…"
        val groups = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val out = ArrayList<List<FileEntry>>()
            files.value.filter { it.size > 0 }.groupBy { it.size }.filter { it.value.size > 1 }.values.forEach { same ->
                same.mapNotNull { e -> try { SafOps.sha256(cr, Uri.parse(e.path)) to e } catch (ex: Exception) { null } }
                    .groupBy({ it.first }, { it.second }).values.filter { it.size > 1 }.forEach { out += it }
            }
            out
        }
        duplicates.value = groups; dupProgress.value = null
    }

    // ---- Organizer execution (plan comes from the pure Organizer; files are MOVED only after user confirmation) ----
    fun runOrganize(plan: OrganizePlan, destTree: Uri, onDone: () -> Unit) = viewModelScope.launch {
        applyAll = null; AdsManager.fileOperationInProgress = true; message.value = "جارٍ التنظيم…"
        val results = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val t = SafTransfer(getApplication(), ::ask)
            val out = ArrayList<OpResult>()
            for ((dir, group) in plan.moves.groupBy { it.targetDir }) {
                val id = try { SafOps.ensureFolder(cr, destTree, dir.split('/').filter { it.isNotBlank() }) }
                catch (e: Exception) { group.forEach { out += OpResult(it.entry, TransferStatus.FAILED, e.message, "") }; continue }
                val r = t.runInDir(group.map { it.entry }, destTree, id, true)
                out += r
                if (r.any { it.status == TransferStatus.CANCELLED }) break
            }
            out
        }
        logResults("تنظيم", results)
        AdsManager.fileOperationInProgress = false
        onDone(); rescan()
    }

    // ---- Bulk rename (SAF documents only) ----
    fun runRename(items: List<RenamePreview>, onDone: () -> Unit) = viewModelScope.launch {
        message.value = "جارٍ إعادة التسمية…"
        val results = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            items.map { p ->
                val u = Uri.parse(p.entry.path)
                when {
                    u.authority == "media" -> OpResult(p.entry, TransferStatus.FAILED, "إعادة التسمية تدعم ملفات المجلدات المضافة فقط", "")
                    else -> SafOps.rename(cr, u, p.newName)?.let { OpResult(p.entry, TransferStatus.DONE, "${p.entry.name} ← ${p.newName}", it.toString()) }
                        ?: OpResult(p.entry, TransferStatus.FAILED, "رفض مزوّد التخزين إعادة التسمية", "")
                }
            }
        }
        val now = System.currentTimeMillis()
        results.zip(items).forEach { (r, p) ->
            db.logs().insert(OperationLog(type = "إعادة تسمية", timeMs = now, oldName = p.entry.name, newName = p.newName, oldPath = p.entry.path,
                newPath = r.newPath, size = p.entry.size, status = r.status.name, reason = if (r.status == TransferStatus.DONE) null else r.reason))
        }
        message.value = "اكتمل: ${results.count { it.status == TransferStatus.DONE }} ناجح، ${results.count { it.status == TransferStatus.FAILED }} فشل"
        onDone(); rescan()
    }

    // ---- Saved organize tasks ----
    val tasks = db.tasks().all().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun saveTask(name: String, json: String) = viewModelScope.launch { db.tasks().put(SavedTaskEntity(name = name, json = json)) }
    fun deleteTask(t: SavedTaskEntity) = viewModelScope.launch { db.tasks().delete(t) }

    // ---- Archives (ZIP only) ----
    val archive = MutableStateFlow<ArchiveState?>(null)

    fun openArchive(e: FileEntry) = viewModelScope.launch {
        if (e.extension != "zip") {
            archive.value = ArchiveState(e, emptyList(), "تنسيق ${e.extension?.uppercase()} غير مدعوم حالياً: لا توجد مكتبة مرخّصة مضافة. تظهر معلومات الملف فقط.")
            return@launch
        }
        val l = withContext(Dispatchers.IO) {
            try { getApplication<Application>().contentResolver.openInputStream(Uri.parse(e.path))?.use { ZipLab.list(it) } ?: ZipLab.Listing(emptyList(), 0, "تعذر فتح الملف") }
            catch (ex: Exception) { ZipLab.Listing(emptyList(), 0, "تعذر فتح الملف: ${ex.message}") }
        }
        archive.value = ArchiveState(e, l.items, listOfNotNull(l.error, if (l.unsafeSkipped > 0) "تم تجاهل ${l.unsafeSkipped} عنصراً بمسار غير آمن" else null).joinToString(" — ").ifEmpty { null })
    }

    /** names == null extracts everything. Existing files are NEVER replaced silently: they are skipped and counted. */
    fun extractArchive(e: FileEntry, names: Set<String>?, destTree: Uri, onDone: () -> Unit) = viewModelScope.launch {
        message.value = "جارٍ فك الضغط…"
        val (counts, note) = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            var done = 0; var skipped = 0; var failed = 0; var note: String? = null
            try {
                (cr.openInputStream(Uri.parse(e.path)) ?: throw java.io.IOException("تعذر فتح الأرشيف")).use { ins ->
                    ZipLab.forEachFile(ins) { name, stream ->
                        if (names != null && name !in names) return@forEachFile
                        var doc: Uri? = null
                        try {
                            val segs = name.split('/')
                            val dirId = SafOps.ensureFolder(cr, destTree, segs.dropLast(1))
                            if (SafOps.childExists(cr, destTree, dirId, segs.last())) skipped++
                            else {
                                doc = SafOps.createFile(cr, destTree, dirId, segs.last()) ?: throw java.io.IOException("تعذر إنشاء الملف")
                                (cr.openOutputStream(doc, "w") ?: throw java.io.IOException("تعذر الكتابة")).use { o -> stream.copyTo(o) }
                                done++
                            }
                        } catch (ex: java.util.zip.ZipException) {
                            doc?.let { runCatching { android.provider.DocumentsContract.deleteDocument(cr, it) } }; throw ex
                        } catch (ex: Exception) {
                            doc?.let { runCatching { android.provider.DocumentsContract.deleteDocument(cr, it) } }; failed++
                        }
                    }
                }
            } catch (ex: java.util.zip.ZipException) {
                note = if (ex.message?.contains("encrypted", true) == true) "الأرشيف محمي بكلمة مرور (غير مدعوم)" else "الأرشيف تالف: ${ex.message}"
            } catch (ex: Exception) { note = "فشل: ${ex.message}" }
            intArrayOf(done, skipped, failed) to note
        }
        val summary = "استُخرج ${counts[0]}، متجاوَز (موجود) ${counts[1]}، فشل ${counts[2]}" + (note?.let { " — $it" } ?: "")
        db.logs().insert(OperationLog(type = "فك ضغط", timeMs = System.currentTimeMillis(), oldName = e.name, newName = "", oldPath = e.path,
            newPath = destTree.toString(), size = e.size, status = if (counts[2] == 0 && note == null) "DONE" else "FAILED", reason = summary))
        message.value = summary
        onDone(); rescan()
    }

    /** Undo is offered only for renames (the log keeps the new document URI + old name). Moves are NOT undoable yet. */
    fun undoRename(l: OperationLog) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { SafOps.rename(getApplication<Application>().contentResolver, Uri.parse(l.newPath), l.oldName) } != null
        if (ok) db.logs().setStatus(l.id, "UNDONE")
        message.value = if (ok) "تم التراجع: ${l.newName} ← ${l.oldName}" else "تعذر التراجع (تغيّر الملف أو رفض النظام)"
        if (ok) rescan()
    }

    // ---- Visually similar images (perceptual hash; local only) ----
    val similar = MutableStateFlow<List<List<FileEntry>>?>(null)
    val similarProgress = MutableStateFlow<String?>(null)
    val similarSkipped = MutableStateFlow(0)

    fun scanSimilar() = viewModelScope.launch {
        similarProgress.value = "جارٍ التحليل…"
        val (groups, skipped) = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val imgs = files.value.filter { it.type == FileType.IMAGE }
            val hashed = ArrayList<Pair<FileEntry, Long>>(); var skipped = 0
            imgs.forEachIndexed { i, e ->
                val h = ImageHasher.hash(cr, Uri.parse(e.path))
                if (h == null) skipped++ else hashed += e to h
                if (i % 25 == 0) similarProgress.value = "تحليل $i/${imgs.size}"
            }
            val byPath = hashed.associate { it.first.path to it.first }
            PerceptualHash.cluster(hashed.map { it.first.path to it.second }, 6).map { g -> g.map { byPath.getValue(it) } } to skipped
        }
        similar.value = groups; similarSkipped.value = skipped; similarProgress.value = null
    }

    val reviewMonths = settings.reviewMonths.stateIn(viewModelScope, SharingStarted.Eagerly, 6)

    // ---- Compare two sources / detect missing files (read-only) ----
    val compareResult = MutableStateFlow<CompareUi?>(null)
    val compareBusy = MutableStateFlow(false)

    private class Loaded(val files: List<RelFile>, val failed: List<String>, val hash: (RelFile) -> String?, val error: String?)

    private fun loadSource(cr: android.content.ContentResolver, s: SourceRef): Loaded {
        if (s.zip) {
            val digest = HashMap<String, String>(); val files = ArrayList<RelFile>(); var err: String? = null
            try {
                (cr.openInputStream(s.uri) ?: throw java.io.IOException("تعذر فتح الأرشيف")).use { ins ->
                    ZipLab.digest(ins).forEach { (n, sz, h) -> files += RelFile(n, sz, null); digest[n] = h }
                }
            } catch (e: Exception) { err = "تعذرت قراءة الأرشيف «${s.label}»: ${e.message}" }
            return Loaded(files, if (err != null) listOf("") else emptyList(), { digest[it.path] }, err)
        }
        val (files, failed) = RelScanner.scan(cr, s.uri)
        return Loaded(files, failed, { f -> f.uri?.let { try { SafOps.sha256(cr, Uri.parse(it)) } catch (e: Exception) { null } } }, null)
    }

    fun runCompare(a: SourceRef, b: SourceRef) = viewModelScope.launch {
        compareBusy.value = true; compareResult.value = null
        val ui = withContext(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val la = loadSource(cr, a); val lb = loadSource(cr, b)
                CompareUi(SourceComparer.compare(la.files, lb.files, la.hash, lb.hash, la.failed, lb.failed),
                    listOfNotNull(la.error, lb.error).joinToString(" — ").ifEmpty { null })
            } catch (e: Exception) { CompareUi(emptyList(), "تعذرت المقارنة: ${e.message}") }
        }
        compareResult.value = ui; compareBusy.value = false
    }
}

data class SourceRef(val uri: Uri, val zip: Boolean, val label: String)
data class CompareUi(val rows: List<CompareRow>, val note: String?)
