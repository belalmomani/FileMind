package com.filemind.smartmanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.filemind.smartmanager.AppViewModel
import com.filemind.smartmanager.BuildConfig
import com.filemind.smartmanager.ads.AdsManager
import com.filemind.smartmanager.core.*
import com.filemind.smartmanager.data.ThemeMode
import kotlinx.coroutines.launch

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, openCategory: (Category) -> Unit, openQuery: (String, FileFilter) -> Unit, openFolders: () -> Unit, openTrash: () -> Unit, openOps: () -> Unit, openSettings: () -> Unit, openStorage: () -> Unit, openOrganizer: () -> Unit, openDuplicates: () -> Unit, openArchives: () -> Unit, openTimeline: () -> Unit, openCompare: () -> Unit, openBrowser: () -> Unit) {
    val ctx = LocalContext.current
    val files by vm.files.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val failed by vm.failedFolders.collectAsState()
    val roots by vm.roots.collectAsState()
    val pending by vm.pendingOps.collectAsState()
    val running by vm.opProgress.collectAsState()
    val now = remember(files) { System.currentTimeMillis() }
    val reviewMonths by vm.reviewMonths.collectAsState()
    val indexTime by vm.indexTime.collectAsState()
    var granted by remember { mutableStateOf(mediaPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    var showRationale by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    var askMsg by remember { mutableStateOf<String?>(null) }
    val gridCols by vm.settings.gridColumns.collectAsState(initial = 0)
    // Removable volumes (USB OTG / SD card) detected through StorageManager; access still needs the user's folder grant (SAF).
    val storageManager = remember { ctx.getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager }
    var volumeTick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() { override fun onReceive(c: android.content.Context?, i: Intent?) { volumeTick++ } }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    val newVolumes = remember(volumeTick, roots, scanning) {
        runCatching {
            storageManager.storageVolumes.filter { v ->
                v.isRemovable && v.state == android.os.Environment.MEDIA_MOUNTED && v.uuid != null && roots.none { r -> r.contains(v.uuid!!, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
    }
    val volumeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.addRoot(uri)
        }
    }
    val bgRunning by com.filemind.smartmanager.data.BackgroundOps.running.collectAsState()
    var bgAfterPermission by remember { mutableStateOf<Long?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Background work runs even if the notification permission is refused.
        bgAfterPermission?.let { com.filemind.smartmanager.data.ResumeOpsWorker.enqueue(ctx, it) }; bgAfterPermission = null
    }
    fun startBackground(id: Long) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            bgAfterPermission = id; notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else com.filemind.smartmanager.data.ResumeOpsWorker.enqueue(ctx, id)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        granted = r.values.all { it }; vm.rescan()
    }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.addRoot(uri)
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == android.app.Activity.RESULT_OK && text != null) {
            ask = text
            val q = ArabicQueryParser.parse(text)
            askMsg = if (q.isConfident) "فهمت: " + q.toFileFilter(System.currentTimeMillis()).describe().joinToString(" · ") + " — اضغط «اسأل» للتأكيد"
            else "لم أفهم «$text». وضّح النوع أو الحجم أو السنة."
        }
    }
    LaunchedEffect(Unit) { vm.rescan() }

    val counts = remember(files, reviewMonths) { Category.entries.associateWith { c ->
        if (c == Category.STALE) files.count { it.modifiedMs != null && now - it.modifiedMs > reviewMonths * 30L * DAY_MS } else files.count { c.test(it, now) }
    } }
    val totalSize = remember(files) { files.sumOf { it.size } }
    val showThumbs by vm.settings.showThumbs.collectAsState(initial = true)
    // Cover of each tile = the newest NON-hidden image/video that belongs to that same category (no image => plain colour tile).
    val covers = remember(files, reviewMonths) {
        val media = files.filter { (it.type == FileType.IMAGE || it.type == FileType.VIDEO) && !it.isHidden }
        Category.entries.associateWith { c ->
            media.filter { if (c == Category.STALE) it.modifiedMs != null && now - it.modifiedMs > reviewMonths * 30L * DAY_MS else c.test(it, now) }
                .maxByOrNull { it.exifDateMs ?: it.modifiedMs ?: 0L }
        }
    }

    // One scrollable grid: header (search, status, buttons) spans the full width, then the category tiles. Nothing is hidden on small screens.
    LazyVerticalGrid(GridCells.Fixed(if (gridCols > 0) gridCols else 2), Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("FileMind", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ask, { ask = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("اسأل ملفاتك: مثل «اعرض كل ملفات ZIP من سنة 2025»") })
            TextButton({
                try {
                    voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar"))
                } catch (e: Exception) { askMsg = "التعرف الصوتي غير متاح على هذا الجهاز" }
            }) { Text("🎤") }
            Button({
                val q = ArabicQueryParser.parse(ask)
                if (q.isConfident) { askMsg = null; openQuery(ask, q.toFileFilter(System.currentTimeMillis())) }
                else askMsg = if (q.unrecognized.isEmpty()) "لم أفهم الطلب. وضّح النوع أو الحجم أو السنة." else "لم أفهم: " + q.unrecognized.joinToString(" ") + " — وضّح المقصود."
            }) { Text("اسأل") }
        }
        askMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // TODO: voice command button (SpeechRecognizer) wired to the same ArabicQueryParser; never run destructive actions from voice without confirmation.

        if (!granted) Card { Column(Modifier.padding(12.dp)) {
            Text("لعرض الصور والفيديو والصوتيات في التصنيفات نحتاج إذن قراءة الوسائط. لن تُرفع ملفاتك لأي خادم.")
            TextButton({ showRationale = true }) { Text("منح الإذن") }
        } }
        if (running == null) newVolumes.forEach { v ->
            Card { Column(Modifier.padding(12.dp)) {
                Text("تم اكتشاف وحدة تخزين خارجية: ${v.getDescription(ctx)}")
                Text("لا يُقرأ أي ملف قبل أن تمنح الوصول بنفسك وتختار المجلد.", style = MaterialTheme.typography.bodySmall)
                TextButton({
                    if (android.os.Build.VERSION.SDK_INT >= 29) volumeLauncher.launch(v.createOpenDocumentTreeIntent()) else treeLauncher.launch(null)
                }) { Text("منح الوصول وإضافتها") }
            } }
        }
        running?.let { Row(verticalAlignment = Alignment.CenterVertically) { Text("جارٍ التنفيذ: $it ملف", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary); TextButton({ vm.stopOp() }) { Text("إيقاف") } } }
        if (running == null) pending.forEach { p ->
            Card { Column(Modifier.padding(12.dp)) {
                Text("عملية غير مكتملة: بقي ${p.remaining} ملف")
                Row {
                    TextButton({ vm.resumeOp(p.opId) }, enabled = !bgRunning) { Text("استكمال") }
                    TextButton({ startBackground(p.opId) }, enabled = !bgRunning) { Text("في الخلفية") }
                    TextButton({ vm.discardOp(p.opId) }, enabled = !bgRunning) { Text("تجاهل") }
                }
                if (bgRunning) Text("تعمل عملية في الخلفية؛ التعارضات فيها تُترك دون استبدال (القديم يبقى) وتُسجَّل كمتجاوَزة. يمكن إيقافها من الإشعار.", style = MaterialTheme.typography.bodySmall)
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ treeLauncher.launch(null) }) { Text("إضافة مجلد / بطاقة SD") }
            OutlinedButton({ vm.rescan() }) { Text(if (scanning) "جارٍ الفحص…" else "إعادة الفحص") }
        }
        Text("${files.size} ملف · ${fmtSize(totalSize)} · ${roots.size} مجلد مضاف" + if (failed.isNotEmpty()) " · تعذر فحص ${failed.size} مجلد" else "", style = MaterialTheme.typography.bodySmall)
        indexTime?.let { Text("الفهرس المحلي (نسخة مخبأة قد تكون قديمة) محدّث: ${fmtDate(it)}" + if (scanning) " — جارٍ التحديث…" else "", style = MaterialTheme.typography.bodySmall) }
        if (failed.isNotEmpty()) Text("المجلدات التي تعذر فحصها غير محسوبة (لا تُعتبر فارغة).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("المتصفح", openBrowser, Modifier.weight(1f))
            HomeButton("المجلدات", openFolders, Modifier.weight(1f))
            HomeButton("العمليات", openOps, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("التخزين", openStorage, Modifier.weight(1f))
            HomeButton("التنظيم", openOrganizer, Modifier.weight(1f))
            HomeButton("المكررات", openDuplicates, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("الأرشيفات", openArchives, Modifier.weight(1f))
            HomeButton("آلة الزمن", openTimeline, Modifier.weight(1f))
            HomeButton("مقارنة / مفقود", openCompare, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("السلة", openTrash, Modifier.weight(1f))
            HomeButton("الإعدادات", openSettings, Modifier.weight(1f))
        }
        } }
            items(Category.entries.toList()) { c ->
                val cover = covers[c]
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(c.argb).copy(alpha = 0.85f)).clickable { openCategory(c) }.fillMaxWidth().heightIn(min = 110.dp)) {
                    if (showThumbs && cover != null && c != Category.HIDDEN) {
                        WallThumb(cover.path, Modifier.matchParentSize(), video = cover.type == FileType.VIDEO)
                        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.65f)))))
                    }
                    Column(Modifier.padding(12.dp)) {
                        Text(if (c == Category.STALE) "تحتاج مراجعة — منذ $reviewMonths أشهر" else c.titleAr, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text("${counts[c]}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
    }
    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false },
        title = { Text("إذن قراءة الوسائط") },
        text = { Text("يُستخدم فقط لعرض الصور والفيديو والصوتيات وتصنيفها على جهازك. الوصول لبقية المجلدات يتم باختيارك عبر «إضافة مجلد».") },
        confirmButton = { TextButton({ showRationale = false; permLauncher.launch(mediaPermissions()) }) { Text("متابعة") } },
        dismissButton = { TextButton({ showRationale = false }) { Text("لاحقاً") } },
    )
}

@Composable
private fun HomeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
        Text(label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FoldersScreen(vm: AppViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val files by vm.files.collectAsState()
    val folders = remember(files) { files.groupBy { it.parent }.entries.sortedByDescending { e -> e.value.sumOf { it.size } } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        TextButton(onBack) { Text("رجوع") }
        LazyColumn {
            items(folders, key = { it.key }) { (parent, list) ->
                val pal = FolderPalette.of(list)
                Row(Modifier.fillMaxWidth().clickable { onOpen(parent) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FolderIcon(pal.colors, pal.hasMore)
                    Column { Text(parent.ifBlank { "(بدون مسار)" }, maxLines = 1); Text("${list.size} ملف · ${fmtSize(list.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun OperationsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val logs by vm.logs.collectAsState()
    val msg by vm.message.collectAsState()
    val running by vm.opProgress.collectAsState()
    var q by remember { mutableStateOf("") }
    val shown = logs.filter { q.isBlank() || it.oldName.contains(q, true) || it.status.contains(q, true) || it.type.contains(q) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row { TextButton(onBack) { Text("رجوع") }; Spacer(Modifier.weight(1f)); TextButton({ vm.clearLog() }) { Text("مسح السجل") } }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("بحث في السجل") })
        running?.let { Row(verticalAlignment = Alignment.CenterVertically) { Text("عملية جارية: $it", Modifier.weight(1f)); TextButton({ vm.stopOp() }) { Text("إيقاف") } } }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (shown.isEmpty()) Text("لا توجد عمليات مسجلة بعد.", Modifier.padding(16.dp))
        // TODO: undo for operations where source/destination info allows; running/paused ops with resume (WorkManager).
        LazyColumn {
            items(shown, key = { it.id }) { l ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${l.type} — ${l.oldName} — ${when (l.status) { "DONE" -> "نجحت"; "SKIPPED" -> "تُجوزت"; "CANCELLED" -> "أُلغيت"; "UNDONE" -> "تم التراجع"; else -> "فشلت" }}")
                    Text("${fmtDate(l.timeMs)} · ${fmtSize(l.size)}" + (l.reason?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text("من: ${l.oldPath}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (l.newPath.isNotEmpty()) Text("إلى: ${l.newPath}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (l.type == "إعادة تسمية" && l.status == "DONE") TextButton({ vm.undoRename(l) }) { Text("تراجع: ${l.newName} ← ${l.oldName}") }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, mode: ThemeMode, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val roots by vm.roots.collectAsState()
    val confirm by vm.settings.confirmDelete.collectAsState(initial = true)
    val retention by vm.settings.logRetentionDays.collectAsState(initial = 0)
    val backup by vm.settings.backupOnReplace.collectAsState(initial = false)
    val reviewMonths by vm.reviewMonths.collectAsState()
    val crash by vm.crashReports.collectAsState()
    val iconSize by vm.settings.iconSize.collectAsState(initial = 40)
    val gridColumns by vm.settings.gridColumns.collectAsState(initial = 0)
    val trashDays by vm.settings.trashDays.collectAsState(initial = 30)
    val photoMap by vm.settings.photoMapEnabled.collectAsState(initial = false)
    val showThumbs by vm.settings.showThumbs.collectAsState(initial = true)
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TextButton(onBack) { Text("رجوع") }; Text("المظهر", style = MaterialTheme.typography.titleMedium) }
        items(ThemeMode.entries.toList()) { m ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { vm.settings.setTheme(m) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == m, { scope.launch { vm.settings.setTheme(m) } })
                Text(when (m) { ThemeMode.LIGHT -> "نهاري"; ThemeMode.DARK -> "ليلي"; ThemeMode.SYSTEM -> "تلقائي (النظام)" })
            }
        }
        item { Text("المجلدات المضافة", style = MaterialTheme.typography.titleMedium) }
        items(roots.toList()) { r -> Row(verticalAlignment = Alignment.CenterVertically) { Text(r, Modifier.weight(1f), maxLines = 1); TextButton({ vm.removeRoot(r) }) { Text("إزالة") } } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(confirm, { scope.launch { vm.settings.setConfirmDelete(it) } }); Text("  تأكيد قبل حذف الملفات") }
            Text("إيقافه يحذف الملفات المحددة مباشرة. حذف المجلدات يُؤكَّد دائماً.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(backup, { scope.launch { vm.settings.setBackupOnReplace(it) } }); Text("  نسخة احتياطية عند الاستبدال") }
            Text("عند التفعيل يُعاد تسمية الملف القديم إلى «.filemind-backup» بدل حذفه.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ vm.clearIndexAndRescan() }) { Text("مسح الفهرس المحلي وإعادة الفحص") }
        }
        item {
            Text("حجم الأيقونات", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(32 to "صغير", 40 to "متوسط", 56 to "كبير").forEach { (d, label) -> FilterChip(iconSize == d, { scope.launch { vm.settings.setIconSize(d) } }, { Text(label) }) }
            }
            Text("عدد الأعمدة في الشبكات", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "تلقائي", 2 to "2", 3 to "3", 4 to "4").forEach { (n, label) -> FilterChip(gridColumns == n, { scope.launch { vm.settings.setGridColumns(n) } }, { Text(label) }) }
            }
            Text("يؤثر على المتصفح (القائمة والشبكة) وعلى تصنيفات الشاشة الرئيسية.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(showThumbs, { scope.launch { vm.settings.setShowThumbs(it) } }); Text("  إظهار مصغّرات الصور في القوائم") }
            Text("أوقفه إن كان الهاتف بطيئاً أو أردت توفير الذاكرة.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("خريطة الصور (اختيارية)", style = MaterialTheme.typography.titleMedium)
            if (photoMap) {
                Text("مفعّلة. مواقع صورك تُقرأ وتُخزَّن على جهازك فقط ولا تُرسل إلى أي جهة.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ vm.disablePhotoMap() }) { Text("إيقاف الخريطة وحذف بيانات الموقع المخزنة") }
            } else Text("مطفأة. تُفعَّل من «عروض ← خريطة الصور» في قوائم الصور بعد موافقتك الصريحة.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("سلة المحذوفات", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(7 to "7 أيام", 30 to "30 يوماً", 90 to "90 يوماً", 0 to "بدون سلة").forEach { (d, label) -> FilterChip(trashDays == d, { scope.launch { vm.settings.setTrashDays(d) } }, { Text(label) }) }
            }
            Text("الحذف من المتصفح ومن الملفات داخل مجلداتك الممنوحة يُنقل إلى السلة ويمكن استرجاعه. «بدون سلة» يعيد الحذف النهائي.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("فترة «تحتاج مراجعة»", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 6, 12, 24).forEach { m -> FilterChip(reviewMonths == m, { scope.launch { vm.settings.setReviewMonths(m) } }, { Text("$m أشهر") }) }
            }
            Text("تُحسب من تاريخ التعديل فقط، ولا يعني ذلك أن الملف غير ضروري.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("الاحتفاظ بسجل العمليات", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "دائماً", 30 to "30 يوم", 90 to "90 يوم", 365 to "سنة").forEach { (d, label) ->
                    FilterChip(retention == d, { scope.launch { vm.settings.setLogRetention(d) } }, { Text(label) })
                }
            }
            Text("يُحذف السجل القديم فقط عند فتح التطبيق، ولا تُحذف ملفاتك أبداً.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("الخصوصية", style = MaterialTheme.typography.titleMedium)
            Text("الفهرسة والبحث والسجل تتم محلياً على جهازك. لا تُرفع أسماء الملفات أو محتوياتها. الإعلانات (إن فُعّلت) تعالجها مكتبة Start.io وفق سياستها.")
            Text("الأوامر الصوتية تستخدم خدمة التعرف الصوتي في نظام جهازك؛ قد يعالج الصوت (لا الملفات) عبر الإنترنت حسب الجهاز. لا تُنفَّذ أي عملية تغيّر الملفات من الصوت دون تأكيد.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(crash, { vm.setCrashReports(it) }); Text("  إرسال تقارير الأعطال (Firebase Crashlytics)") }
            Text("معطّل افتراضياً. عند تفعيله تُرسل عند حدوث عطل معلومات تقنية فقط (نوع الجهاز والنظام وسجل العطل ومعرّف تثبيت)، دون أسماء ملفاتك أو محتوياتها.", style = MaterialTheme.typography.bodySmall)
            Text("حالة Start.io: " + if (AdsManager.isConfigured) "مهيّأ" else "غير مهيّأ (أضف startio.appId في local.properties)")
            Text("الإصدار ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            // TODO: indexing, voice, log retention, cloud, backup-before-replace settings.
        }
    }
}
