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
            acc to fail
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
}
