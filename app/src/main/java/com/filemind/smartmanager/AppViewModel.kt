package com.filemind.smartmanager

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
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
import kotlinx.coroutines.Job
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
    val crashReports = settings.crashReports.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Crashlytics is opt-in. Safe when Firebase isn't configured (no google-services.json): the call is simply ignored. */
    private fun applyCrashReports(on: Boolean) {
        runCatching { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(on) }
    }
    fun setCrashReports(on: Boolean) = viewModelScope.launch { settings.setCrashReports(on); applyCrashReports(on) }

    val roots = settings.roots.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        // Auto-purge of expired trash (only trashed items with a known deletion time; never anything else).
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5000)
            runCatching { TrashStore.purge(getApplication<Application>().contentResolver, settings.roots.first(), settings.trashDays.first()) }
        }
        viewModelScope.launch { applyCrashReports(settings.crashReports.first()) }
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
        if (failed.isEmpty()) withContext(Dispatchers.IO) {
            runCatching {
                db.index().replaceAll(all.map { IndexedFile(it.path, it.name, it.size, it.modifiedMs, it.exifDateMs, it.parent) })
                settings.setIndexTime(System.currentTimeMillis())
            }
        }
    }

    val indexTime = settings.indexTime.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Show the cached index instantly; Home triggers a fresh rescan right away, and the cache is never treated as truth.
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { runCatching { db.index().all() }.getOrDefault(emptyList()) }
            if (cached.isNotEmpty() && files.value.isEmpty()) files.value = cached.map { FileEntry(it.path, it.name, it.size, it.modifiedMs, it.exifDateMs, parent = it.parent) }
        }
    }

    fun clearIndexAndRescan() = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { db.index().clear(); settings.setIndexTime(0) } }
        files.value = emptyList(); rescan()
    }

    fun addRoot(uri: Uri) = viewModelScope.launch { settings.addRoot(uri.toString()); rescan() }
    fun removeRoot(uri: String) = viewModelScope.launch { settings.removeRoot(uri); rescan() }

    suspend fun loadFilter(key: String): FileFilter = db.filters().get(key)?.let { FileFilter.fromJson(it.json) } ?: FileFilter()
    fun saveFilter(key: String, f: FileFilter) = viewModelScope.launch { db.filters().put(SavedFilterEntity(key, key, f.toJson())) }
    fun resetFilter(key: String) = viewModelScope.launch { db.filters().reset(key) }

    val namedFilters = db.namedFilters().all().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    /** Same name overwrites = edit. */
    fun saveNamedFilter(name: String, f: FileFilter) = viewModelScope.launch { db.namedFilters().put(NamedFilter(name, f.toJson())) }
    fun deleteNamedFilter(n: NamedFilter) = viewModelScope.launch { db.namedFilters().delete(n) }

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

    // ---- Journaled bulk operations: every item is stored first, marked done one by one, resumable after interruption ----
    val pendingOps = db.pending().remainingByOp().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val opProgress = MutableStateFlow<String?>(null)
    @Volatile private var stopRequested = false
    /** Cooperative stop: finishes the current file, leaves the rest pending so it can be resumed. */
    fun stopOp() { stopRequested = true; message.value = "جارٍ الإيقاف بعد الملف الحالي…" }

    private suspend fun createOp(kind: String, destTree: Uri, items: List<Pair<FileEntry, String>>): Long {
        val id = db.pending().insertOp(PendingOp(kind = kind, destTree = destTree.toString(), createdMs = System.currentTimeMillis()))
        db.pending().insertItems(items.map { (e, dir) -> PendingItem(opId = id, path = e.path, name = e.name, size = e.size, modifiedMs = e.modifiedMs, parent = e.parent, destDir = dir) })
        return id
    }

    private suspend fun executePending(opId: Long) {
        val op = db.pending().op(opId) ?: return
        val items = db.pending().remaining(opId)
        val total = db.pending().total(opId)
        applyAll = null; stopRequested = false; AdsManager.fileOperationInProgress = true
        var done = total - items.size
        opProgress.value = "$done/$total"; message.value = null
        val backup = settings.backupOnReplace.first()
        BackgroundOps.uiRunning = true
        val stats = try {
            withContext(Dispatchers.IO) {
                PendingRunner.run(getApplication(), db, opId, backup, ::ask, { stopRequested }) { d, t -> opProgress.value = "$d/$t" }
            }
        } finally { BackgroundOps.uiRunning = false }
        val remaining = db.pending().remaining(opId).size
        if (remaining == 0) { db.pending().deleteItems(opId); db.pending().deleteOp(opId) }
        AdsManager.fileOperationInProgress = false; opProgress.value = null
        message.value = "اكتمل: ${stats[0]} ناجح، ${stats[1]} متجاوَز، ${stats[2]} فشل" + if (remaining > 0) " — بقي $remaining ملف (يمكن الاستكمال من الشاشة الرئيسية)" + (if (stopRequested) " — أُوقفت بطلبك" else "") else ""
        rescan()
    }

    fun transfer(sources: List<FileEntry>, destTree: Uri, move: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        val id = createOp(if (move) "MOVE" else "COPY", destTree, sources.map { it to "" })
        executePending(id); onDone()
    }

    /** Copy/move of files AND folders from the browser. Folders are expanded to their files first; nothing is started if any folder can't be read. */
    fun transferWithFolders(srcTree: Uri, files: List<BItem>, folders: List<BItem>, parentLabel: String, destTree: Uri, move: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        val cr = getApplication<Application>().contentResolver
        val destRoot = android.provider.DocumentsContract.getTreeDocumentId(destTree)
        if (folders.any { FolderGuard.isSameOrInside(destRoot, it.docId) }) { message.value = "لا يمكن نسخ/نقل مجلد إلى نفسه أو إلى مجلد فرعي منه"; return@launch }
        message.value = "جارٍ تحليل المجلدات…"
        val walks = withContext(Dispatchers.IO) { folders.map { f -> FolderLister.walk(cr, srcTree, f.docId, f.name) } }
        val failed = walks.sumOf { it.failed.size }
        if (failed > 0) { message.value = "تعذر قراءة $failed مجلد داخل المحدد؛ أُلغيت العملية حفاظاً على ملفاتك"; return@launch }
        withContext(Dispatchers.IO) { walks.forEach { w -> w.emptyDirs.forEach { d -> runCatching { SafOps.ensureFolder(cr, destTree, d.split('/')) } } } }
        val items = ArrayList<Pair<FileEntry, String>>()
        files.forEach { items += FileEntry(it.uri, it.name, it.size, it.modified, parent = parentLabel) to "" }
        walks.forEach { w -> w.files.forEach { (b, rel) -> items += FileEntry(b.uri, b.name, b.size, b.modified, parent = rel) to rel } }
        if (items.isEmpty()) { message.value = "لا توجد ملفات للمعالجة (أُنشئت بنية المجلدات الفارغة فقط)"; onDone(); return@launch }
        val id = createOp(if (move) "MOVE" else "COPY", destTree, items)
        executePending(id)
        if (move) withContext(Dispatchers.IO) { folders.forEach { SafOps.removeEmptyTree(cr, srcTree, it.docId) } }
        onDone()
    }

    /**
     * Creates a NEW zip (never overwrites; name made unique) in [destTree] from the selected files/folders.
     * All-or-nothing: any unreadable file, write failure, stop request or failed verification deletes the partial zip.
     */
    fun zipSelection(srcTree: Uri, files: List<BItem>, folders: List<BItem>, destTree: Uri, zipName: String, onDone: () -> Unit) = viewModelScope.launch {
        if (BackgroundOps.running.value) { message.value = "تعمل عملية في الخلفية الآن؛ انتظر انتهاءها"; return@launch }
        val cr = getApplication<Application>().contentResolver
        val destRoot = android.provider.DocumentsContract.getTreeDocumentId(destTree)
        message.value = "جارٍ تحليل المحدد…"
        val walks = withContext(Dispatchers.IO) { folders.map { f -> FolderLister.walk(cr, srcTree, f.docId, f.name) } }
        if (walks.sumOf { it.failed.size } > 0) { message.value = "تعذر قراءة مجلد داخل المحدد؛ أُلغي الضغط حفاظاً على الدقة"; return@launch }
        class Src(val name: String, val item: BItem?)
        val raw = ArrayList<Src>()
        files.forEach { raw += Src(it.name, it) }
        walks.forEach { w ->
            w.emptyDirs.forEach { raw += Src("$it/", null) }
            w.files.forEach { (b, rel) -> raw += Src("$rel/${b.name}", b) }
        }
        if (raw.isEmpty()) { message.value = "لا يوجد ما يُضغط"; return@launch }
        val names = ZipMaker.uniqueNames(raw.map { it.name.replace('\\', '_') })
        val entries = raw.mapIndexed { i, s ->
            val b = s.item
            ZipMaker.Entry(names[i], b == null, b?.modified) { b?.let { x -> cr.openInputStream(Uri.parse(x.uri)) } }
        }
        val total = entries.count { !it.isDir }
        // New, unique file name at the destination root.
        val base = zipName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".zip").ifBlank { "archive" }
        var finalName = "$base.zip"; var k = 2
        while (withContext(Dispatchers.IO) { SafOps.childExists(cr, destTree, destRoot, finalName) }) finalName = "$base ($k).zip".also { k++ }

        stopRequested = false; AdsManager.fileOperationInProgress = true; BackgroundOps.uiRunning = true
        opProgress.value = "0/$total"; message.value = null
        var target: Uri? = null
        val result = withContext(Dispatchers.IO) {
            target = SafOps.createFile(cr, destTree, destRoot, finalName)
            val t = target ?: return@withContext ZipMaker.Outcome(0, "تعذر إنشاء ملف الأرشيف في الوجهة (تحقق من صلاحية الكتابة)", false)
            val os = (try { cr.openOutputStream(t, "w") } catch (e: Exception) { null })
                ?: return@withContext ZipMaker.Outcome(0, "تعذر فتح الوجهة للكتابة", false)
            os.buffered().use { ZipMaker.write(entries, it, { stopRequested }) { d, n -> opProgress.value = "$d/$n" } }
        }
        var ok = result.error == null && !result.stopped
        var reason: String? = result.error ?: if (result.stopped) "أُوقف بطلبك" else null
        if (ok) {
            val listing = withContext(Dispatchers.IO) { runCatching { cr.openInputStream(target!!)?.use { ZipLab.list(it) } }.getOrNull() }
            if (listing == null || listing.error != null || listing.items.size != entries.size) { ok = false; reason = "فشل التحقق من الأرشيف بعد إنشائه" }
        }
        if (!ok) target?.let { u -> withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(cr, u) } } }
        db.logs().insert(OperationLog(type = "ضغط", timeMs = System.currentTimeMillis(), oldName = "$total ملف", newName = finalName, oldPath = srcTree.toString(),
            newPath = if (ok) target.toString() else "", size = 0, status = if (ok) "DONE" else "FAILED", reason = reason))
        AdsManager.fileOperationInProgress = false; BackgroundOps.uiRunning = false; opProgress.value = null
        message.value = if (ok) "تم إنشاء $finalName ($total ملف) وتم التحقق منه" else "لم يُنشأ الأرشيف: $reason (لا يُترك ملف ناقص)"
        rescan(); onDone()
    }

    fun resumeOp(id: Long) = viewModelScope.launch { if (BackgroundOps.running.value) message.value = "تعمل عملية في الخلفية الآن؛ انتظر انتهاءها أو أوقفها من الإشعار" else executePending(id) }
    fun discardOp(id: Long) = viewModelScope.launch { db.pending().deleteItems(id); db.pending().deleteOp(id) }

    /** Logs the outcome of a delete performed by the UI layer (MediaStore delete request or SAF delete). */
    fun logDelete(entries: List<FileEntry>, ok: Boolean, reason: String? = null) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        entries.forEach { e -> db.logs().insert(OperationLog(type = "حذف", timeMs = now, oldName = e.name, newName = "", oldPath = e.path, newPath = "", size = e.size, status = if (ok) "DONE" else "FAILED", reason = reason)) }
        if (ok) rescan()
    }

    // ---- Color explorer: low-resolution color analysis of photos, resumable through an on-disk cache ----
    val colorIndex = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ColorProfile>>(emptyMap())
    val colorProgress = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Int>?>(null)
    private var colorJob: Job? = null

    fun stopColorAnalysis() { colorJob?.cancel() }

    fun analyzeColors(photos: List<WallPhoto>) {
        if (colorJob?.isActive == true) return
        colorJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>(); val cr = app.contentResolver
            val cache = ColorCache.load(app)
            fun sig(p: WallPhoto) = p.dateMs?.toString() ?: "-"
            val found = HashMap<String, ColorProfile>()
            for (p in photos) cache[p.path]?.let { (s, pr) -> if (s == sig(p)) found[p.path] = pr }
            colorIndex.value = HashMap(found)
            val todo = photos.filter { it.path !in found }
            val total = photos.size
            var done = total - todo.size
            colorProgress.value = done to total
            val permits = Semaphore(2)
            try {
                kotlinx.coroutines.coroutineScope {
                    todo.map { p ->
                        async {
                            permits.withPermit {
                                ensureActive()
                                val pr = runCatching { ColorWork.profileOf(cr, p.path) }.getOrNull()
                                synchronized(found) {
                                    if (pr != null) { found[p.path] = pr; cache[p.path] = sig(p) to pr }
                                    done++
                                }
                                if (done % 25 == 0) { colorIndex.value = synchronized(found) { HashMap(found) }; colorProgress.value = done to total }
                            }
                        }
                    }.forEach { it.await() }
                }
            } finally {
                kotlinx.coroutines.withContext(NonCancellable) {
                    colorIndex.value = synchronized(found) { HashMap(found) }
                    colorProgress.value = null
                    ColorCache.save(app, cache)
                }
            }
        }
    }

    /** Set by the file list just before opening the photo wall (plain field: read once when the wall opens). */
    var wallRequest: WallRequest? = null

    /**
     * Moves entries into the in-app trash when the trash is on AND a granted folder covers them. [onLeft] receives the entries that were NOT handled
     * (no covering folder, or trash off) so the caller can continue with the old flow. Entries whose trashing failed are NOT deleted: they stay and are reported.
     */
    fun deleteWithTrash(entries: List<FileEntry>, onLeft: (List<FileEntry>) -> Unit) = viewModelScope.launch {
        val days = settings.trashDays.first()
        if (days <= 0) { onLeft(entries); return@launch }
        val cr = getApplication<Application>().contentResolver
        val rootSet = roots.value
        val left = ArrayList<FileEntry>(); var moved = 0; var failed = 0
        withContext(Dispatchers.IO) {
            for (e in entries) {
                val docId = TrashStore.docIdOf(e); val tree = docId?.let { TrashStore.rootFor(rootSet, it) }
                if (docId == null || tree == null) { left += e; continue }
                val ok = TrashStore.trash(cr, tree, docId, TrashStore.parentDocId(docId), e.name, e.size, false)
                if (ok) moved++ else failed++
                logOp("سلة", e.name, "", e.path, "", ok, if (!ok) "تعذر النقل إلى السلة (لم يُحذف شيء)" else null)
            }
        }
        if (moved > 0) rescan()
        val parts = ArrayList<String>()
        if (moved > 0) parts += "نُقل $moved ملف إلى السلة (تُحذف نهائياً بعد $days يوماً)"
        if (failed > 0) parts += "تعذر نقل $failed ملف إلى السلة فبقي كما هو"
        if (parts.isNotEmpty()) message.value = parts.joinToString("، ")
        onLeft(left)
    }

    /** Splits MediaStore entries into (deletable through the system confirmation, everything else). Below Android 11 all go the old way. */
    fun splitMedia(entries: List<FileEntry>): Pair<List<FileEntry>, List<FileEntry>> {
        if (android.os.Build.VERSION.SDK_INT < 30) return entries to emptyList()
        val cr = getApplication<Application>().contentResolver
        val system = entries.filter { SafOps.isRealMedia(cr, Uri.parse(it.path)) }
        return system to (entries - system.toSet())
    }

    /** The generic "Files" URI is not accepted by the system delete request; rebuild it as the type-specific collection URI (image/audio/video) with the same id and volume. */
    private fun typedMediaUri(cr: android.content.ContentResolver, uri: Uri): Uri {
        val id = android.content.ContentUris.parseId(uri)
        val vol = uri.pathSegments.firstOrNull() ?: "external"
        val type = cr.query(uri, arrayOf("media_type"), null, null, null)?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
        val base = when (type) {
            1 -> android.provider.MediaStore.Images.Media.getContentUri(vol)
            2 -> android.provider.MediaStore.Audio.Media.getContentUri(vol)
            3 -> android.provider.MediaStore.Video.Media.getContentUri(vol)
            else -> return uri
        }
        return android.content.ContentUris.withAppendedId(base, id)
    }

    /**
     * Builds the system delete-confirmation for real media items (Android 11+). Returns null when the system refuses; in that case the
     * granted-folder route is tried, the outcome is logged, and the message shows the exact refusal reason.
     */
    fun buildDeleteRequest(entries: List<FileEntry>): android.content.IntentSender? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        val cr = getApplication<Application>().contentResolver
        return try {
            android.provider.MediaStore.createDeleteRequest(cr, entries.map { typedMediaUri(cr, Uri.parse(it.path)) }).intentSender
        } catch (ex: Exception) {
            val detail = "${ex.javaClass.simpleName}: ${ex.message?.take(140)}"
            logDelete(entries, false, "رفض النظام طلب الحذف: $detail")
            deleteViaGrantedFolders(entries)
            message.value = (message.value ?: "تعذر الحذف عبر النظام") + " [$detail]"
            null
        }
    }

    /** Deletes non-media MediaStore entries (subtitles, documents, unknown) through a granted folder. Logs both outcomes and reports what could not be deleted. */
    fun deleteViaGrantedFolders(entries: List<FileEntry>) {
        val cr = getApplication<Application>().contentResolver
        val done = entries.filter { SafOps.deleteViaRoots(cr, roots.value, it) }
        val left = entries - done.toSet()
        if (done.isNotEmpty()) logDelete(done, true)
        if (left.isNotEmpty()) {
            logDelete(left, false, "لا يوجد مجلد ممنوح يغطي مسار الملف")
            message.value = "تعذر حذف ${left.size} ملف ليس صورة أو فيديو أو صوتاً: أضف مجلده (مثل Download/Film) من «إضافة مجلد» ثم أعد المحاولة"
        }
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
        val id = createOp("ORGANIZE", destTree, plan.moves.map { it.entry to it.targetDir })
        executePending(id); onDone()
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
    fun renameTask(t: SavedTaskEntity, name: String) = viewModelScope.launch { db.tasks().put(t.copy(name = name)) }
    fun deleteTask(t: SavedTaskEntity) = viewModelScope.launch { db.tasks().delete(t) }

    // ---- Archives (ZIP only) ----
    val archive = MutableStateFlow<ArchiveState?>(null)

    fun openArchive(e: FileEntry) = viewModelScope.launch {
        val ext = e.extension
        if (ext != "zip" && ext != "7z" && ext != "rar") {
            archive.value = ArchiveState(e, emptyList(), "صيغة ${ext?.uppercase()} غير مدعومة للقراءة. يمكنك فتحها بتطبيق آخر.")
            return@launch
        }
        val l = withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            try {
                when (ext) {
                    "7z" -> ArchiveReaders.list7z(cr, Uri.parse(e.path))
                    "rar" -> ArchiveReaders.listRar(cr, Uri.parse(e.path))
                    else -> cr.openInputStream(Uri.parse(e.path))?.use { ZipLab.list(it) } ?: ZipLab.Listing(emptyList(), 0, "تعذر فتح الملف")
                }
            } catch (ex: Exception) { ZipLab.Listing(emptyList(), 0, "تعذر فتح الملف: ${ex.message}") }
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

    /** Simple one-row log entry for single operations done in the browser (rename, new folder, delete). */
    fun logOp(type: String, oldName: String, newName: String, oldPath: String, newPath: String, ok: Boolean, reason: String? = null) = viewModelScope.launch {
        db.logs().insert(OperationLog(type = type, timeMs = System.currentTimeMillis(), oldName = oldName, newName = newName, oldPath = oldPath, newPath = newPath,
            size = 0, status = if (ok) "DONE" else "FAILED", reason = reason))
    }

    val reviewMonths = settings.reviewMonths.stateIn(viewModelScope, SharingStarted.Eagerly, 6)

    // ---- Compare two sources / detect missing files (read-only) ----
    val compareResult = MutableStateFlow<CompareUi?>(null)
    val compareBusy = MutableStateFlow(false)

    private class Loaded(val files: List<RelFile>, val failed: List<String>, val hash: (RelFile) -> String?, val error: String?)

    private suspend fun loadSource(cr: android.content.ContentResolver, s: SourceRef): Loaded {
        if (s.snapshotId != null) {
            val snap = db.snapshots().get(s.snapshotId) ?: return Loaded(emptyList(), listOf(""), { null }, "المرجع المحفوظ غير موجود")
            val arr = org.json.JSONArray(snap.json); val files = ArrayList<RelFile>(); val hashes = HashMap<String, String>()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); files += RelFile(o.getString("p"), o.getLong("s"), null); hashes[o.getString("p")] = o.getString("h") }
            return Loaded(files, emptyList(), { hashes[it.path] }, null)
        }
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


    /** Copies files that exist on only one side into the other folder (same relative paths). Never overwrites or deletes; journaled/resumable like other bulk ops. */
    fun copyMissing(a: SourceRef, b: SourceRef, fromA: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        val src = if (fromA) a else b; val dst = if (fromA) b else a
        if (src.zip || dst.zip || src.snapshotId != null || dst.snapshotId != null) { message.value = "النسخ متاح بين مجلدين فقط (ليس أرشيفاً أو مرجعاً محفوظاً)"; return@launch }
        if (FolderGuard.isSameOrInside(src.label, dst.label) || FolderGuard.isSameOrInside(dst.label, src.label)) { message.value = "لا يمكن الدمج بين مجلد ومجلد داخله"; return@launch }
        val picked = MergePlan.missing(compareResult.value?.rows.orEmpty(), fromA)
        val items = picked.mapNotNull { r ->
            val f = (if (fromA) r.a else r.b) ?: return@mapNotNull null
            val dir = MergePlan.dirOf(r.path)
            FileEntry(f.uri ?: return@mapNotNull null, r.path.substringAfterLast('/'), f.size, f.modifiedMs, parent = dir) to dir
        }
        if (items.isEmpty()) { message.value = "لا توجد ملفات مفقودة للنسخ"; return@launch }
        val id = createOp("COPY", dst.uri, items)
        executePending(id); onDone()
    }

    // ---- Saved reference sets (local snapshots) ----
    val snapshots = db.snapshots().all().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun deleteSnapshot(s: SnapshotEntity) = viewModelScope.launch { db.snapshots().delete(s) }

    fun saveSnapshot(src: SourceRef, name: String) = viewModelScope.launch {
        compareBusy.value = true; message.value = "جارٍ حفظ المرجع…"
        val text = withContext(Dispatchers.IO) {
            try {
                val l = loadSource(getApplication<Application>().contentResolver, src)
                val arr = org.json.JSONArray(); var skipped = 0
                l.files.forEach { f -> val h = l.hash(f); if (h == null) skipped++ else arr.put(org.json.JSONObject().put("p", f.path).put("s", f.size).put("h", h)) }
                db.snapshots().put(SnapshotEntity(name = name, createdMs = System.currentTimeMillis(), json = arr.toString()))
                "تم حفظ المرجع «$name»: ${arr.length()} ملف" + (if (skipped > 0) " (تعذر $skipped)" else "") +
                    (if (l.failed.isNotEmpty() || l.error != null) " — تنبيه: بعض المجلدات لم تُفحص فالمرجع ناقص" else "")
            } catch (e: Exception) { "تعذر حفظ المرجع: ${e.message}" }
        }
        message.value = text; compareBusy.value = false
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

data class SourceRef(val uri: Uri, val zip: Boolean, val label: String, val snapshotId: Long? = null)
data class CompareUi(val rows: List<CompareRow>, val note: String?)
