package com.filemind.smartmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.filemind.smartmanager.ads.AdsManager
import com.filemind.smartmanager.core.Category
import com.filemind.smartmanager.core.FileFilter
import com.filemind.smartmanager.data.ThemeMode
import com.filemind.smartmanager.ui.*

sealed interface Screen {
    data object Home : Screen
    data object Folders : Screen
    data object Ops : Screen
    data object Settings : Screen
    data object Storage : Screen
    data object Organizer : Screen
    data object Duplicates : Screen
    data object Archives : Screen
    data object Timeline : Screen
    data object Similar : Screen
    data object Compare : Screen
    data class Files(val key: String, val title: String, val category: Category?, val filter: FileFilter?, val folder: String? = null, val custom: ((com.filemind.smartmanager.core.FileEntry, Long) -> Boolean)? = null) : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdsManager.init()
        setContent {
            val mode by vm.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val conflict by vm.conflict.collectAsState()
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            fun push(s: Screen) { stack.add(s) }
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            BackHandler(stack.size > 1) { pop() }

            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                FileMindTheme(mode) {
                    Surface(Modifier.fillMaxSize()) {
                        when (val s = stack.last()) {
                            Screen.Home -> HomeScreen(vm,
                                openCategory = { c ->
                                    if (c == Category.STALE) {
                                        val m = vm.reviewMonths.value
                                        push(Screen.Files("CAT_STALE_$m", "تحتاج مراجعة — منذ $m أشهر", c, null,
                                            custom = { e, now -> e.modifiedMs != null && now - e.modifiedMs > m * 30L * com.filemind.smartmanager.core.DAY_MS }))
                                    } else push(Screen.Files("CAT_${c.name}", c.titleAr, c, null))
                                },
                                openQuery = { text, f -> push(Screen.Files("ASK_$text", "نتيجة: $text", null, f)) },
                                openFolders = { push(Screen.Folders) }, openOps = { push(Screen.Ops) }, openSettings = { push(Screen.Settings) },
                                openStorage = { push(Screen.Storage) }, openOrganizer = { push(Screen.Organizer) }, openDuplicates = { push(Screen.Duplicates) }, openArchives = { push(Screen.Archives) }, openTimeline = { push(Screen.Timeline) }, openCompare = { push(Screen.Compare) })
                            Screen.Folders -> FoldersScreen(vm, onOpen = { p -> push(Screen.Files("FOLDER_$p", p, null, FileFilter(folderContains = p), p)) }, onBack = ::pop)
                            Screen.Ops -> OperationsScreen(vm, ::pop)
                            Screen.Settings -> SettingsScreen(vm, mode, ::pop)
                            Screen.Storage -> StorageScreen(vm, openType = { t -> push(Screen.Files("TYPE_${t.name}", t.name, null, com.filemind.smartmanager.core.FileFilter(types = setOf(t)))) }, openFolder = { p -> push(Screen.Files("FOLDER_$p", p, null, com.filemind.smartmanager.core.FileFilter(folderContains = p), p)) }, onBack = ::pop)
                            Screen.Organizer -> OrganizerScreen(vm, ::pop)
                            Screen.Duplicates -> DuplicatesScreen(vm, ::pop, openSimilar = { push(Screen.Similar) })
                            Screen.Similar -> SimilarImagesScreen(vm, ::pop)
                            Screen.Compare -> CompareScreen(vm, ::pop)
                            Screen.Archives -> ArchivesScreen(vm, ::pop)
                            Screen.Timeline -> TimelineScreen(vm, openMonth = { t, pred -> push(Screen.Files("TL_$t", t, null, null, custom = pred)) }, onBack = ::pop)
                            is Screen.Files -> FileListScreen(vm, s.title, s.key,
                                base = s.custom ?: s.category?.test ?: { _, _ -> true }, initial = s.filter, onBack = ::pop)
                        }
                    }
                    conflict?.let { c -> ConflictDialog(c) { d, all -> vm.answerConflict(d, all) } }
                }
            }
        }
    }
}
