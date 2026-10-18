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
        logResults("إعادة تسمية", results)
        onDone(); rescan()
    }
}
