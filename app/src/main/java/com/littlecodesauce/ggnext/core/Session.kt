package com.littlecodesauce.ggnext.core

import android.app.Application
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.data.AppPaths
import com.littlecodesauce.ggnext.data.HistoryPersistence
import com.littlecodesauce.ggnext.data.LocalBlobStore
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.data.SavedListFileCodec
import com.littlecodesauce.ggnext.data.ScriptHistoryEntry
import com.littlecodesauce.ggnext.data.SearchHistory
import com.littlecodesauce.ggnext.data.SettingKeys
import com.littlecodesauce.ggnext.data.SettingsRepository
import com.littlecodesauce.ggnext.data.SettingsSpecs
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.engine.Shell
import com.littlecodesauce.ggnext.model.AsmArch
import com.littlecodesauce.ggnext.model.AsmPackStatus
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.model.BpSession
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.MemoryRegion
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.DEFAULT_PINNED_TOOLS
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_DEBUG
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_MEMORY
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SAVED
import com.littlecodesauce.ggnext.ui.model.DEFAULT_TOOLS_SEARCH
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import com.littlecodesauce.ggnext.ui.model.ToolId
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import com.littlecodesauce.ggnext.ui.theme.UiMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** 六个导航页（含 AI 对话） */
enum class NextTab(val label: String) {
    SETTINGS("设置"),
    SEARCH_LIST("搜索列表"),
    SAVED_LIST("保存列表"),
    MEMORY("内存"),
    DEBUG("调试"),
    AI("对话")
}

/** 主页底部三个导航页（主页 / 扩展 / 设置） */
enum class HomeTab(val label: String) {
    HOME("主页"),
    EXTENSIONS("扩展"),
    SETTINGS("设置")
}

/** 工作模式枚举：Root 模式 与 免 Root 模式（共享 UID / 虚拟空间沙盒） */
enum class WorkMode(val code: String, val title: String, val desc: String) {
    ROOT("root", "Root 模式", "通过 Root 权限进行全系统跨进程读写（支持所有已运行应用与游戏）"),
    NO_ROOT("no_root", "免 Root 模式", "针对虚拟空间/双开沙箱（所有应用共享相同 UID），无需 Root 跨进程读写")
}

enum class Sheet {
    NONE,
    PROCESS,
    SEARCH,
    FUZZY,
    MASK_SEARCH,
    POINTER_SEARCH,
    FILTER,
    REGION,
    VALUE_FORMAT,
    EDITOR,
    OFFSET,
    CALC_OFFSET,
    REMOVE,
    TOOLBAR_ORDER,
    TOOLBAR_PICK,
    GOTO,
    MODULE_LIST,
    SCRIPT,
    FILE_BROWSER,
    SPEED,
    HISTORY,
    FUNCTION,
    LOG,
    OVERLAY,
    HELP,
    ABOUT,
    CHANGELOG,
    LANGUAGE,
    APPEARANCE,
    CONTEXT,
    TYPE_PICK,
    ADDRESS_EDIT,
    FILE_MANAGER,
    COPY_MEMORY,
    DUMP,
    ALLOCATE,
    BREAKPOINT,
    WRITE_ASM
}

/** 列表文件操作模式：严格区分只读载入与只写保存/导出 */
enum class FileSheetMode {
    LOAD,
    SAVE,
    EXPORT
}

/** 文件浏览器打开参数与回调目标 */
enum class FileBrowserTarget {
    SCRIPT,
    FILE_MANAGER,
    DUMP,
    FONT,
    ASM_PACK
}

data class FileBrowserConfig(
    val currentDir: String = AppPaths.DOWNLOAD,
    val selectDirectory: Boolean = false,
    val returnSheet: Sheet = Sheet.SCRIPT,
    val target: FileBrowserTarget = FileBrowserTarget.SCRIPT
)

/** 独立高频搜索进度状态（脱离 Ui，避免每 45ms 触发全界面重组） */
data class SearchProgressUi(
    val active: Boolean = false,
    val text: String = "",
    val progress: Float = 0f,
    val foundSoFar: Long = 0L,
    val etaSeconds: Int = -1,
    val scannedMb: Float = 0f,
    val totalMb: Float = 0f
)

/** 搜索面板输入 */
data class SearchInput(
    val text: String = "",
    val type: Int = ValueType.DWORD,
    val sign: Int = ValueType.SIGN_EQUAL,
    val mask: String = "FFFFFFFFFFFFFFFF",
    val offset: String = "0",
    val offsetHex: Boolean = true,
    val rangeMode: Int = 0, // 0=全部内存, 1=指定地址区间, 2=附近
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val pointerType: Int = ValueType.QWORD,
    val encrypted: Boolean = false,
    val expanded: Boolean = false,
    val keyboardVisible: Boolean = true
)

/** 模糊搜索状态 */
data class FuzzyState(
    val active: Boolean = false,
    val type: Int = ValueType.DWORD,
    val rangeMode: Int = 0,
    val rangeFrom: String = "00000000",
    val rangeTo: String = "FFFFFFFFFFFFFFFF",
    val nearbyAddress: String = "",
    val nearbyBefore: Boolean = true,
    val nearbyAfter: Boolean = true,
    val nearbyDistance: String = "500",
    val extended: Boolean = false,
    val deltaText: String = "",
    val onlyNth: String = "",
    val keyboardVisible: Boolean = true
) {
    val rangeLabel: String
        get() = when (rangeMode) {
            1 -> "指定地址区间"
            2 -> "附近:"
            else -> "全部内存"
        }
}

/** 结果过滤/筛选状态 */
data class FilterState(
    val maxShow: Int = 2000,
    val addrGreatEnabled: Boolean = false,
    val addrGreat: String = "",
    val addrLessEnabled: Boolean = false,
    val addrLess: String = "",
    val valGreatEnabled: Boolean = false,
    val valGreat: String = "",
    val valLessEnabled: Boolean = false,
    val valLess: String = "",
    val typeEnabled: Boolean = false,
    val types: Set<Int> = emptySet(),
    val fractionalEnabled: Boolean = false,
    val fractionalNotEqual: Boolean = false,
    val fractional: String = "",
    val pointerEnabled: Boolean = false,
    val pointers: Set<Int> = emptySet(), // 4="-", 8="RO", 16="W", 2="X", 1="WX"
    val formatHex: Boolean = false,
    val formatRHex: Boolean = false,
    val formatString: Boolean = false,
    val formatUtf16: Boolean = false
) {
    val isAnyFilterActive: Boolean
        get() = addrGreatEnabled || addrLessEnabled || valGreatEnabled || valLessEnabled ||
            typeEnabled || fractionalEnabled || pointerEnabled
}

/** 内存区域汇总行 */
data class RegionRow(
    val code: String,
    val name: String,
    val size: Long,
    val count: Int,
    val selected: Boolean = false
)

data class Ui(
    val tab: NextTab = NextTab.SEARCH_LIST,
    val rawResults: List<MemoryItem> = emptyList(),
    val results: List<MemoryItem> = emptyList(),
    val saved: List<MemoryItem> = emptyList(),
    val memoryRows: List<MemoryItem> = emptyList(),
    val memoryBase: Long = 0L,
    val memoryHighlightAddress: Long? = null,
    val memoryScrollTrigger: Int = 0,
    val memoryTop: Long = 0L,
    val memoryOffsetShift: Int = 0,
    val memoryHistoryBack: List<Long> = emptyList(),
    val memoryHistoryForward: List<Long> = emptyList(),

    // O(1) 独立分标签选择状态（模块 4）
    val searchSelection: SelectionState = SelectionState.EMPTY,
    val savedSelection: SelectionState = SelectionState.EMPTY,
    val memorySelection: SelectionState = SelectionState.EMPTY,

    val processes: List<TargetProcess> = emptyList(),
    val processFilter: Int = 0, // 0=全部, 1=用户, 2=系统
    val processSortBySize: Boolean = true,
    val processLoading: Boolean = true,
    val target: TargetProcess? = null,
    val processPaused: Boolean = false,

    val regions: List<RegionRow> = emptyList(),
    val selectedRegions: Set<String> = RegionCatalog.DEFAULT_SELECTED,

    val busy: Boolean = false,
    val busyText: String = "",
    val isBatchBusy: Boolean = false,

    /** 分层弹窗导航栈（支持子弹窗逐级返回） */
    val sheetStack: List<Sheet> = emptyList(),
    val fileSheetMode: FileSheetMode = FileSheetMode.SAVE,

    val search: SearchInput = SearchInput(),
    val fuzzy: FuzzyState = FuzzyState(),
    val filter: FilterState = FilterState(),

    val scriptPath: String = AppPaths.SCRIPT_FILE,
    val scriptHistory: List<ScriptHistoryEntry> = HistoryPersistence.DEFAULT_SCRIPT_HISTORY,
    val fileManagerPath: String = AppPaths.FILE_MANAGER_FILE,
    val dumpDirPath: String = AppPaths.DUMP_DIR,
    val fileBrowser: FileBrowserConfig = FileBrowserConfig(),

    val valueMask: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val valueMaskSaved: Int = SettingsSpecs.DEFAULT_FORMAT_MASK,
    val searchType: Int = ValueType.DWORD,

    val settings: Map<String, String> = emptyMap(),
    val settingsLoaded: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val metrics: UiMetrics = UiMetrics(),
    val preset: ThemePreset = ThemePreset.ROSE,
    val darkMode: DarkMode = DarkMode.DARK,
    val dynamicColor: Boolean = false,
    val customFontPath: String = "",
    val customFontName: String = "",
    val managedFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList(),

    val tools: Map<NextTab, List<ToolId>> = emptyMap(),
    val pinnedTools: Map<NextTab, List<ToolId>> = emptyMap(),
    val totalCount: Long = 0,
    val hintVisible: Boolean = true,
    val workMode: WorkMode = WorkMode.ROOT,
    val hasRootPermission: Boolean = false,
    val attachedOk: Boolean = false,
    val editing: MemoryItem? = null,
    val editSingleOnly: Boolean = false,
    val lastBatchEditValue: String = "",
    val lastBatchEditStep: String = "",
    val contextItem: MemoryItem? = null,
    val history: List<String> = emptyList(),
    val gotoHistory: List<String> = emptyList(),
    val floatingBallVisible: Boolean = false,
    val panelVisible: Boolean = false,
    val speedBarExpanded: Boolean = false,
    val speedMultiplier: Float = 1.0f,
    val speedWrapEnabled: Boolean = false,
    val homeSettingsOpen: Boolean = false,
    val homeTab: HomeTab = HomeTab.HOME,
    val breakpoints: List<BpSession> = emptyList(),
    val selectedBpIds: Set<Int> = emptySet(),
    val asmPack: AsmPackStatus = AsmPackStatus(),
    val memoryDisasm: Map<Long, String> = emptyMap()
) {
    /** 当前栈顶弹窗（派生属性，保持调用方兼容） */
    val sheet: Sheet get() = sheetStack.lastOrNull() ?: Sheet.NONE

    val searchSelectedCount: Int get() = searchSelection.selectedCount(results.size)
    val savedSelectedCount: Int get() = savedSelection.selectedCount(saved.size)
    val memorySelectedCount: Int get() = memorySelection.selectedCount(memoryRows.size)

    /** O(1) 当前标签已选数量 */
    val selectedCount: Int
        get() = when (tab) {
            NextTab.SAVED_LIST -> savedSelectedCount
            NextTab.MEMORY -> memorySelectedCount
            NextTab.SEARCH_LIST -> searchSelectedCount
            NextTab.DEBUG -> selectedBpIds.size
            NextTab.SETTINGS, NextTab.AI -> 0
        }

    fun isSearchItemSelected(item: MemoryItem): Boolean =
        searchSelection.isSelected(SelectionState.itemKey(item))

    fun isSavedItemSelected(item: MemoryItem): Boolean =
        savedSelection.isSelected(SelectionState.itemKey(item))

    fun isMemoryRowSelected(item: MemoryItem): Boolean =
        memorySelection.isSelected(SelectionState.memoryRowKey(item))

    val currentMask: Int get() = if (tab == NextTab.SAVED_LIST) valueMaskSaved else valueMask
    val currentPinnedTools: List<ToolId> get() = pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS
    val flowingTools: List<ToolId>
        get() {
            val base = tools[tab] ?: when (tab) {
                NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
                NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
                NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
                NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
                NextTab.SETTINGS, NextTab.AI -> emptyList()
            }
            val pinned = currentPinnedTools.toSet()
            return base.filter { it !in pinned }
        }
    val searchToolList: List<ToolId> get() = flowingTools

    val countButtonText: String
        get() {
            val total = maxOf(totalCount, rawResults.size.toLong(), results.size.toLong())
            return "(${results.size}/$total)"
        }

    val speedText: String
        get() = if (speedMultiplier >= 10f || speedMultiplier == speedMultiplier.toInt().toFloat()) {
            String.format(Locale.US, "%.1fx", speedMultiplier)
        } else {
            String.format(Locale.US, "%.2fx", speedMultiplier).trimEnd('0').let {
                if (it.endsWith(".")) "${it}0x" else "${it}x"
            }
        }
}

/**
 * 全局会话：主界面与悬浮窗共享同一份数据与引擎。
 */
object Session {

    const val MEMORY_PAGE_BYTES = 4096
    const val MEMORY_PAGE_ROWS = MEMORY_PAGE_BYTES / 4

    private val SPEED_STEPS = floatArrayOf(
        0.05f, 0.1f, 0.2f, 0.25f, 0.33f, 0.5f, 0.75f,
        1.0f,
        1.25f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 8.0f, 10.0f, 15.0f, 20.0f, 30.0f, 50.0f, 100.0f
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var repo: SettingsRepository? = null
    private var appContext: Application? = null
    private var blobStoreDir: File? = null
    private val engine = MemoryEngine()
    private val historyStore = SearchHistory()

    @Volatile
    private var hasFuzzySession: Boolean = false
    @Volatile
    private var liveRefreshRunning: Boolean = false
    @Volatile
    private var frozenItemsSnapshot: List<MemoryItem> = emptyList()
    @Volatile
    private var activeSearchJob: Job? = null

    private val pendingSettingsBuffer = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val settingsFlushMutex = Mutex()
    @Volatile
    private var persistSettingsJob: Job? = null
    @Volatile
    private var persistSearchHistJob: Job? = null
    @Volatile
    private var persistGotoHistJob: Job? = null
    @Volatile
    private var persistScriptHistJob: Job? = null
    @Volatile
    private var persistSavedListJob: Job? = null
    @Volatile
    private var bpPollingJob: Job? = null

    private val attachMutex = Mutex()
    private val listScrollPositions = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun getListScrollPosition(key: String): Pair<Int, Int> =
        listScrollPositions[key] ?: (0 to 0)

    fun saveListScrollPosition(key: String, index: Int, offset: Int) {
        listScrollPositions[key] = index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _searchProgressState = MutableStateFlow(SearchProgressUi())
    val searchProgressState: StateFlow<SearchProgressUi> = _searchProgressState.asStateFlow()

    private val _toastState = MutableStateFlow<String?>(null)
    val toastState: StateFlow<String?> = _toastState.asStateFlow()

    private fun buildMetrics(map: Map<String, String>): UiMetrics = UiMetrics(
        fontScale = (map["font_scale"]?.toFloatOrNull() ?: 0.88f).coerceIn(0.7f, 1.3f),
        dialogCorner = (map["dialog_corner"]?.toIntOrNull() ?: 12).coerceIn(0, 28),
        sheetBlur = map["sheet_blur"]?.toIntOrNull() ?: 110,
        iconBlur = map["icon_blur"]?.toIntOrNull() ?: 139,
        skeletonLoading = map["skeleton"] != "0",
        bgOpacity = (map["bg_opacity"]?.toFloatOrNull() ?: 0.96f).coerceIn(0.25f, 1.0f),
        panelScale = (map["panel_scale"]?.toFloatOrNull() ?: 1.0f).coerceIn(0.40f, 1.0f)
    )

    private fun currentHistoryLimit(settings: Map<String, String> = _ui.value.settings): Int =
        (settings["history_limit"]?.toIntOrNull() ?: 500).coerceIn(0, 100000)

    private fun parseRegionsCsv(csv: String?): Set<String>? {
        if (csv.isNullOrBlank()) return null
        val validCodes = RegionCatalog.ALL.mapTo(HashSet()) { it.code }
        val parsed = csv.split(',')
            .map { it.trim() }
            .filter { it in validCodes }
            .toSet()
        return parsed.ifEmpty { null }
    }

    private fun packageRegionKey(packageName: String?): String? {
        val clean = packageName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return "pkg_regions_$clean"
    }

    private fun resolveRegionsForPackage(settings: Map<String, String>, packageName: String?): Set<String> {
        val pkgKey = packageRegionKey(packageName)
        if (pkgKey != null) {
            parseRegionsCsv(settings[pkgKey])?.let { return it }
        }
        parseRegionsCsv(settings["default_selected_regions"])?.let { return it }
        return RegionCatalog.DEFAULT_SELECTED
    }

    /** 将隐蔽读取配置下发到当前原生后端。通道轮换默认开、读后即关 fd 默认关（吞吐优先）。 */
    private fun applyStealthFromSettings() {
        val s = _ui.value.settings
        val rotation = (s["stealth_rotation"] ?: "1") != "0"
        val ephemeral = s["stealth_ephemeral_fd"] == "1"
        engine.configureStealth(rotation, ephemeral)
    }

    private fun enqueueSettingWrite(id: String, value: String, immediate: Boolean = false) {
        pendingSettingsBuffer[id] = value
        scheduleSettingsFlush(delayMs = if (immediate) 0L else 140L)
    }

    private fun enqueueSettingsWrite(pairs: Map<String, String>, immediate: Boolean = false) {
        if (pairs.isEmpty()) return
        pendingSettingsBuffer.putAll(pairs)
        scheduleSettingsFlush(delayMs = if (immediate) 0L else 140L)
    }

    private fun scheduleSettingsFlush(delayMs: Long) {
        persistSettingsJob?.cancel()
        persistSettingsJob = scope.launch(Dispatchers.IO) {
            if (delayMs > 0L) delay(delayMs)
            settingsFlushMutex.withLock {
                withContext(NonCancellable) {
                    while (pendingSettingsBuffer.isNotEmpty()) {
                        val batch = LinkedHashMap<String, String>()
                        val iter = pendingSettingsBuffer.entries.iterator()
                        while (iter.hasNext()) {
                            val entry = iter.next()
                            batch[entry.key] = entry.value
                            iter.remove()
                        }
                        if (batch.isNotEmpty()) {
                            runCatching { repo?.setStrings(batch) }
                        }
                    }
                }
            }
        }
    }

    private fun persistSelectedRegions(
        regions: Set<String>,
        packageName: String?,
        immediate: Boolean = false
    ) {
        val csv = regions.joinToString(",")
        val pkgKey = packageRegionKey(packageName)
        val pairs = LinkedHashMap<String, String>(2)
        pairs["default_selected_regions"] = csv
        if (pkgKey != null) {
            pairs[pkgKey] = csv
        }
        _ui.update { st -> st.copy(settings = st.settings + pairs) }
        enqueueSettingsWrite(pairs, immediate = immediate)
    }

    private fun parseToolOrderCsv(csv: String?): List<ToolId>? {
        if (csv.isNullOrBlank()) return null
        val byName = ToolId.entries.associateBy { it.name }
        val list = csv.split(',').mapNotNull { byName[it.trim()] }.distinct()
        return list.ifEmpty { null }
    }

    private fun persistToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "toolbar_order_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        enqueueSettingWrite(key, csv)
    }

    private fun persistPinnedToolsForTab(tab: NextTab, list: List<ToolId>) {
        val key = "pinned_tools_${tab.name}"
        val csv = list.joinToString(",") { it.name }
        _ui.update { st -> st.copy(settings = st.settings + (key to csv)) }
        enqueueSettingWrite(key, csv)
    }

    private fun persistSearchHistoryAsync() {
        val snapshot = historyStore.exportEntries()
        persistSearchHistJob?.cancel()
        persistSearchHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeSearchHistory(snapshot)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_SEARCH_JSON, json)
        }
    }

    private fun persistGotoHistoryAsync(entries: List<String>) {
        persistGotoHistJob?.cancel()
        persistGotoHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeGotoHistory(entries)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_GOTO_JSON, json)
        }
    }

    private fun persistScriptHistoryAsync(entries: List<ScriptHistoryEntry>) {
        persistScriptHistJob?.cancel()
        persistScriptHistJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeScriptHistory(entries)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_HISTORY_SCRIPT_JSON, json)
        }
    }

    private fun persistSavedListAsync(items: List<MemoryItem>) {
        persistSavedListJob?.cancel()
        persistSavedListJob = scope.launch(Dispatchers.IO) {
            delay(150L)
            val json = HistoryPersistence.encodeSavedList(items)
            LocalBlobStore.writeTextAtomic(blobStoreDir, SettingKeys.KEY_SAVED_LIST_JSON, json)
        }
    }

    private fun syncFrozenSnapshot(savedList: List<MemoryItem>) {
        frozenItemsSnapshot = if (savedList.isEmpty()) emptyList() else savedList.filter { it.freeze }
    }

    /** 统一处理目标进程退出/死亡回调 */
    private fun handleTargetProcessDead() {
        val hadAttach = _ui.value.attachedOk
        if (!hadAttach) return
        scope.launch {
            withContext(Dispatchers.IO) { engine.clearNativeFreezes() }
            _ui.update { st ->
                if (!st.attachedOk) st
                else st.copy(attachedOk = false, processPaused = false, busy = false)
            }
            _searchProgressState.value = SearchProgressUi()
            toast("目标进程已退出或重启，请重新附加")
        }
    }

    fun init(app: Application) {
        appContext = app
        // 统一配置路径：Download/GGNext（日志、脚本、dump、导出都在此根目录）
        AppPaths.ensureDirs()
        // 尽早启用文件持久化日志与全局崩溃捕获（幂等）
        AppLog.init(app)
        blobStoreDir = File(app.filesDir, "ggnext_store")
        engine.setOnTargetProcessDead { handleTargetProcessDead() }
        if (repo != null) return
        val r = SettingsRepository(app)
        repo = r

        // 模块 1：绝不在主线程执行 Shell.hasRoot()，异步移至 Dispatchers.IO
        scope.launch(Dispatchers.IO) {
            val rootOk = Shell.hasRoot()
            _ui.update { st ->
                val savedMode = st.settings["work_mode"]
                val resolvedMode = when (savedMode) {
                    "no_root" -> WorkMode.NO_ROOT
                    "root" -> WorkMode.ROOT
                    else -> if (rootOk) WorkMode.ROOT else WorkMode.NO_ROOT
                }
                st.copy(hasRootPermission = rootOk, workMode = resolvedMode)
            }
            val asmStatus = AsmEngineManager.autoLoad(app)
            _ui.update { it.copy(asmPack = asmStatus) }
        }

        // 启动时单次磁盘水合（避免 DataStore 写入回声反复覆盖内存中的实时修改/区域勾选），
        // 同时将旧版保存在 DataStore 中的大体积 JSON 自动迁移至独立文件存储，防止 Protobuf 序列化 OOM。
        scope.launch(Dispatchers.IO) {
            val map = runCatching { r.settingsSnapshot().first() }.getOrDefault(emptyMap())
            val vMask = runCatching { r.valueFormatMask().first() }.getOrDefault(SettingsSpecs.DEFAULT_FORMAT_MASK)
            val sMask = runCatching { r.savedFormatMask().first() }.getOrDefault(SettingsSpecs.DEFAULT_FORMAT_MASK)

            val limit = (map["history_limit"]?.toIntOrNull() ?: 500).coerceIn(0, 100000)
            historyStore.setLimit(limit)
            map["language"]?.let { lang -> AppStrings.setLang(lang) }

            val blobKeys = listOf(
                SettingKeys.KEY_HISTORY_SEARCH_JSON,
                SettingKeys.KEY_HISTORY_GOTO_JSON,
                SettingKeys.KEY_HISTORY_SCRIPT_JSON,
                SettingKeys.KEY_SAVED_LIST_JSON
            )
            val legacyKeysToPurge = ArrayList<String>(4)
            fun loadBlobWithMigration(key: String): String? {
                val fromFile = LocalBlobStore.readText(blobStoreDir, key)
                val fromLegacy = map[key]
                if (fromLegacy != null) {
                    legacyKeysToPurge.add(key)
                    if (fromFile == null && fromLegacy.isNotBlank()) {
                        LocalBlobStore.writeTextAtomic(blobStoreDir, key, fromLegacy)
                    }
                }
                return fromFile ?: fromLegacy
            }

            val searchJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_SEARCH_JSON)
            val gotoJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_GOTO_JSON)
            val scriptJson = loadBlobWithMigration(SettingKeys.KEY_HISTORY_SCRIPT_JSON)
            val savedJson = loadBlobWithMigration(SettingKeys.KEY_SAVED_LIST_JSON)

            if (legacyKeysToPurge.isNotEmpty()) {
                runCatching { r.removeKeys(legacyKeysToPurge) }
            }

            val decodedSearch = HistoryPersistence.decodeSearchHistory(searchJson)
            val restoredSearchHist: List<String>? = if (decodedSearch.isNotEmpty()) {
                historyStore.restore(decodedSearch)
                historyStore.list()
            } else null

            val decodedGoto = HistoryPersistence.decodeGotoHistory(gotoJson)
            val restoredGotoHist: List<String>? = if (decodedGoto.isNotEmpty()) {
                if (limit > 0) decodedGoto.take(limit.coerceAtMost(200)) else emptyList()
            } else null

            val restoredScriptHist: List<ScriptHistoryEntry>? = if (scriptJson != null) {
                HistoryPersistence.decodeScriptHistory(scriptJson)
            } else null

            val decodedSaved = HistoryPersistence.decodeSavedList(savedJson)
            val restoredSavedItems: List<MemoryItem>? = if (decodedSaved.isNotEmpty()) {
                syncFrozenSnapshot(decodedSaved)
                decodedSaved
            } else null

            val cleanMap = LinkedHashMap<String, String>(map.size)
            for ((k, v) in map) {
                if (k !in blobKeys) {
                    cleanMap[k] = v
                }
            }

            _ui.update { st ->
                // 内存中已发生的实时修改（st.settings）优先于磁盘旧快照，防止启动瞬间点击被回滚
                val merged = cleanMap + st.settings
                val savedMode = merged["work_mode"]
                val resolvedMode = when (savedMode) {
                    "no_root" -> WorkMode.NO_ROOT
                    "root" -> WorkMode.ROOT
                    else -> if (st.hasRootPermission) WorkMode.ROOT else WorkMode.NO_ROOT
                }
                val restoredMaxShow = (merged["filter_max_show"]?.toIntOrNull() ?: st.filter.maxShow).coerceIn(1, 100000)
                val restoredSearchType = merged["search_type"]?.toIntOrNull() ?: st.search.type
                val restoredPointerType = merged["pointer_type"]?.toIntOrNull() ?: st.search.pointerType
                val restoredOffsetHex = merged["pointer_offset_hex"]?.let { it != "0" } ?: st.search.offsetHex
                val restoredFuzzyType = merged["fuzzy_type"]?.toIntOrNull() ?: st.fuzzy.type
                val restoredProcFilter = (merged["process_filter"]?.toIntOrNull() ?: st.processFilter).coerceIn(0, 2)
                val restoredProcSort = merged["process_sort_by_size"]?.let { it != "0" } ?: st.processSortBySize
                val restoredSpeed = (merged["speed_value"]?.toFloatOrNull() ?: st.speedMultiplier).coerceIn(0.01f, 100f)
                val restoredScriptPath = merged["script_path"]?.takeIf { it.isNotBlank() } ?: st.scriptPath
                val restoredFilePath = merged["file_manager_path"]?.takeIf { it.isNotBlank() } ?: st.fileManagerPath
                val restoredDumpDir = merged["dump_dir_path"]?.takeIf { it.isNotBlank() } ?: st.dumpDirPath
                val restoredRegions = resolveRegionsForPackage(merged, st.target?.packageName)

                val restoredTools = st.tools.toMutableMap()
                val restoredPinned = st.pinnedTools.toMutableMap()
                listOf(NextTab.SEARCH_LIST, NextTab.SAVED_LIST, NextTab.MEMORY, NextTab.DEBUG).forEach { t ->
                    parseToolOrderCsv(merged["toolbar_order_${t.name}"])?.let { restoredTools[t] = it }
                    parseToolOrderCsv(merged["pinned_tools_${t.name}"])?.let { restoredPinned[t] = it }
                }

                val savedFontPath = merged["custom_font_path"].orEmpty()
                val validFontPath = if (savedFontPath.isNotBlank() && File(savedFontPath).isFile) savedFontPath else ""
                val validFontName = if (validFontPath.isNotBlank()) {
                    merged["custom_font_name"].orEmpty().ifBlank { File(validFontPath).name }
                } else ""
                val savedFontList = com.littlecodesauce.ggnext.ui.theme.AppFontCache.decodeFontList(merged["managed_fonts_json"])
                val reconciledFonts = com.littlecodesauce.ggnext.ui.theme.AppFontCache.reconcileManagedFonts(
                    context = appContext,
                    saved = savedFontList,
                    activePath = validFontPath,
                    activeName = validFontName
                )

                st.copy(
                    settings = merged,
                    settingsLoaded = true,
                    disclaimerAccepted = merged["disclaimer_v1_accepted"] == "1",
                    metrics = buildMetrics(merged),
                    preset = ThemePreset.entries.firstOrNull { p -> p.name == merged["theme_preset"] } ?: st.preset,
                    darkMode = DarkMode.entries.firstOrNull { d -> d.name == merged["dark_mode"] } ?: st.darkMode,
                    dynamicColor = merged["dynamic_color"] == "1",
                    customFontPath = validFontPath,
                    customFontName = validFontName,
                    managedFonts = reconciledFonts,
                    workMode = resolvedMode,
                    selectedRegions = restoredRegions,
                    filter = st.filter.copy(maxShow = restoredMaxShow),
                    valueMask = vMask,
                    valueMaskSaved = sMask,
                    searchType = restoredSearchType,
                    search = st.search.copy(
                        type = restoredSearchType,
                        pointerType = restoredPointerType,
                        offsetHex = restoredOffsetHex
                    ),
                    fuzzy = st.fuzzy.copy(type = restoredFuzzyType),
                    processFilter = restoredProcFilter,
                    processSortBySize = restoredProcSort,
                    speedMultiplier = restoredSpeed,
                    scriptPath = restoredScriptPath,
                    fileManagerPath = restoredFilePath,
                    dumpDirPath = restoredDumpDir,
                    tools = restoredTools,
                    pinnedTools = restoredPinned,
                    history = restoredSearchHist ?: st.history,
                    gotoHistory = restoredGotoHist ?: st.gotoHistory,
                    scriptHistory = restoredScriptHist ?: st.scriptHistory,
                    saved = restoredSavedItems ?: st.saved
                )
            }
        }

        // JVM 回退锁值与冻结间隔同步循环
        scope.launch(Dispatchers.IO) {
            var lastSyncedInterval = -1L
            while (true) {
                val rawFreeze = _ui.value.settings["freeze_interval"]?.toLongOrNull() ?: 33L
                val intervalMs = (if (rawFreeze > 1000L) rawFreeze / 1000L else rawFreeze).coerceIn(5L, 5000L)
                if (intervalMs != lastSyncedInterval) {
                    lastSyncedInterval = intervalMs
                    engine.setFreezeIntervalMs(intervalMs.toInt())
                }
                delay(intervalMs)
                val frozen = frozenItemsSnapshot
                if (frozen.isNotEmpty() && !_ui.value.processPaused && _ui.value.attachedOk) {
                    engine.tickFreeze(frozen)
                }
            }
        }

        // 实时刷新 + 周期性目标进程存活检测
        scope.launch(Dispatchers.IO) {
            var livenessTick = 0
            while (true) {
                delay(900L)
                val st = _ui.value
                if (st.attachedOk && !st.busy) {
                    livenessTick++
                    if (livenessTick >= 4) {
                        livenessTick = 0
                        if (!engine.isTargetProcessAlive()) {
                            handleTargetProcessDead()
                            continue
                        }
                    }
                    if (st.sheet == Sheet.NONE) {
                        if (st.results.isNotEmpty() || st.saved.isNotEmpty() || (st.tab == NextTab.MEMORY && st.memoryRows.isNotEmpty())) {
                            refreshLiveValues(manual = false)
                        }
                    }
                } else {
                    livenessTick = 0
                }
            }
        }
        loadProcesses(app)
    }

    // ============ 异步数据读取封装（UI 绝不直接访问 Session.engine） ============

    suspend fun readMemoryPreview(address: Long, size: Int): ByteArray =
        withContext(Dispatchers.IO) {
            engine.readBlock(address, size.coerceIn(1, 4096))
        }

    suspend fun loadProcessRegions(): List<MemoryRegion> =
        withContext(Dispatchers.IO) {
            engine.regions()
        }

    fun firstRegionBase(code: String): Long? = engine.firstRegionBase(code)

    // ============ 工作模式与 Root 检测（全部异步 IO） ============

    fun setWorkMode(mode: WorkMode) {
        _ui.update { it.copy(workMode = mode, settings = it.settings + ("work_mode" to mode.code)) }
        enqueueSettingWrite("work_mode", mode.code, immediate = true)
        scope.launch(Dispatchers.IO) {
            val hasRoot = if (mode == WorkMode.ROOT) {
                Shell.resetRootCache()
                Shell.hasRoot()
            } else {
                _ui.value.hasRootPermission
            }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(workMode = mode, hasRootPermission = hasRoot) }
                appContext?.let { loadProcesses(it) }
                val target = _ui.value.target
                if (target != null) {
                    attach(target, silent = true, forceReattach = true)
                }
                toast(
                    when (mode) {
                        WorkMode.ROOT -> if (hasRoot) "已切换至 Root 模式" else "已切换至 Root 模式，未检测到 Root 授权"
                        WorkMode.NO_ROOT -> "已切换至免 Root 模式"
                    }
                )
            }
        }
    }

    fun refreshRootStatus(promptIfMissing: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            Shell.resetRootCache()
            val hasRoot = Shell.hasRoot()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(hasRootPermission = hasRoot) }
                appContext?.let { loadProcesses(it) }
                val target = _ui.value.target
                if (target != null) {
                    attach(target, silent = true, forceReattach = true)
                }
                if (hasRoot) {
                    toast("Root 权限已获取")
                } else if (promptIfMissing) {
                    toast("未检测到 Root 权限，请在管理器中授权或切换到免 Root 模式")
                }
            }
        }
    }

    // ============ 分层弹窗导航栈 ============

    private val childStackSheets = setOf(
        Sheet.HISTORY,
        Sheet.FILE_BROWSER,
        Sheet.MODULE_LIST,
        Sheet.TYPE_PICK,
        Sheet.TOOLBAR_PICK,
        Sheet.ADDRESS_EDIT
    )

    fun pushSheet(sheet: Sheet, item: MemoryItem? = null, singleEdit: Boolean = false) {
        if (sheet == Sheet.NONE) {
            closeAllSheets()
            return
        }
        if (sheet == Sheet.PROCESS) {
            appContext?.let { loadProcesses(it) }
        } else if (sheet == Sheet.REGION) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { aggregate(engine.regions()) }
                _ui.update { it.copy(regions = fresh) }
            }
        }
        _ui.update { st ->
            val nextStack = if (st.sheetStack.lastOrNull() == sheet) st.sheetStack else st.sheetStack + sheet
            st.copy(
                sheetStack = nextStack,
                editing = item ?: st.editing,
                editSingleOnly = singleEdit && item != null,
                contextItem = item ?: st.contextItem
            )
        }
    }

    fun openSheet(sheet: Sheet, item: MemoryItem? = null, singleEdit: Boolean = false) {
        if (sheet == Sheet.NONE) {
            closeAllSheets()
            return
        }
        if (sheet in childStackSheets && _ui.value.sheetStack.isNotEmpty()) {
            pushSheet(sheet, item, singleEdit)
            return
        }
        if (sheet == Sheet.PROCESS) {
            appContext?.let { loadProcesses(it) }
        } else if (sheet == Sheet.REGION) {
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { aggregate(engine.regions()) }
                _ui.update { it.copy(regions = fresh) }
            }
        }
        _ui.update {
            it.copy(
                sheetStack = listOf(sheet),
                editing = item ?: it.editing,
                editSingleOnly = singleEdit && item != null,
                contextItem = item ?: it.contextItem
            )
        }
    }

    fun openFileSheet(mode: FileSheetMode) {
        _ui.update { st ->
            st.copy(
                fileSheetMode = mode,
                sheetStack = listOf(Sheet.FILE_MANAGER)
            )
        }
    }

    fun popSheet(): Boolean {
        val stack = _ui.value.sheetStack
        if (stack.isEmpty()) return false
        _ui.update { st ->
            val popped = st.sheetStack.dropLast(1)
            st.copy(
                sheetStack = popped,
                editSingleOnly = if (popped.lastOrNull() == Sheet.EDITOR) st.editSingleOnly else false
            )
        }
        return true
    }

    fun closeAllSheets() {
        _ui.update { it.copy(sheetStack = emptyList(), editSingleOnly = false) }
    }

    fun closeSheet() {
        popSheet()
    }

    fun openHistorySheet(fromSheet: Sheet = _ui.value.sheet) {
        if (fromSheet != Sheet.NONE && fromSheet != Sheet.HISTORY && _ui.value.sheetStack.isEmpty()) {
            _ui.update { it.copy(sheetStack = listOf(fromSheet, Sheet.HISTORY)) }
        } else {
            pushSheet(Sheet.HISTORY)
        }
    }

    fun recordGotoHistory(hex: String) {
        val clean = hex.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H").uppercase()
        if (clean.isEmpty()) return
        val limit = currentHistoryLimit()
        if (limit <= 0) return
        val cap = limit.coerceAtMost(200)
        var updatedList: List<String> = emptyList()
        _ui.update { st ->
            val list = (listOf(clean) + st.gotoHistory.filter { h -> h != clean }).take(cap)
            updatedList = list
            st.copy(gotoHistory = list)
        }
        persistGotoHistoryAsync(updatedList)
    }

    /** 供 UI 查询任意地址所属内存区域码（寄存器着色等）。 */
    fun regionCodeAt(address: Long): String = engine.regionCodeAt(address)

    fun toast(msg: String) {
        _toastState.value = AppStrings.tr(msg)
    }

    fun consumeToast() {
        _toastState.value = null
    }

    fun selectTab(tab: NextTab) {
        _ui.update {
            if (it.tab == tab) it else it.copy(tab = tab)
        }
        if (tab == NextTab.MEMORY && _ui.value.memoryRows.isEmpty()) {
            goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
        }
    }

    // ============ 文件浏览器与脚本路径管理（持久化 + 诚实提示） ============

    fun openFileBrowser(
        initialPath: String,
        selectDirectory: Boolean,
        returnSheet: Sheet,
        target: FileBrowserTarget
    ) {
        val dir = initialPath.trim().let { p ->
            if (p.isEmpty()) AppPaths.DOWNLOAD
            else if (selectDirectory) p
            else p.substringBeforeLast('/', AppPaths.DOWNLOAD).ifEmpty { AppPaths.DOWNLOAD }
        }
        _ui.update { st ->
            val baseStack = if (st.sheetStack.lastOrNull() == returnSheet) {
                st.sheetStack
            } else {
                listOf(returnSheet)
            }
            st.copy(
                sheetStack = baseStack + Sheet.FILE_BROWSER,
                fileBrowser = FileBrowserConfig(
                    currentDir = dir,
                    selectDirectory = selectDirectory,
                    returnSheet = returnSheet,
                    target = target
                )
            )
        }
    }

    fun onFileBrowserPicked(selectedPath: String) {
        val target = _ui.value.fileBrowser.target
        when (target) {
            FileBrowserTarget.SCRIPT -> {
                setSetting("script_path", selectedPath)
                addOrTouchScriptHistory(selectedPath)
            }
            FileBrowserTarget.FILE_MANAGER -> setSetting("file_manager_path", selectedPath)
            FileBrowserTarget.DUMP -> setSetting("dump_dir_path", selectedPath)
            FileBrowserTarget.FONT -> {
                importCustomFontFromFile(selectedPath)
            }
            FileBrowserTarget.ASM_PACK -> {
                importAsmPackFromFile(selectedPath)
            }
        }
        _ui.update { st ->
            val nextStack = if (st.sheetStack.size > 1 && st.sheetStack.lastOrNull() == Sheet.FILE_BROWSER) {
                st.sheetStack.dropLast(1)
            } else {
                listOf(st.fileBrowser.returnSheet)
            }
            when (st.fileBrowser.target) {
                FileBrowserTarget.SCRIPT -> st.copy(scriptPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.FILE_MANAGER -> st.copy(fileManagerPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.DUMP -> st.copy(dumpDirPath = selectedPath, sheetStack = nextStack)
                FileBrowserTarget.FONT, FileBrowserTarget.ASM_PACK -> st.copy(sheetStack = nextStack)
            }
        }
    }

    fun setScriptPath(path: String) {
        setSetting("script_path", path)
        _ui.update { it.copy(scriptPath = path) }
    }

    fun setFileManagerPath(path: String) {
        setSetting("file_manager_path", path)
        _ui.update { it.copy(fileManagerPath = path) }
    }

    fun setDumpDirPath(path: String) {
        setSetting("dump_dir_path", path)
        _ui.update { it.copy(dumpDirPath = path) }
    }

    private fun addOrTouchScriptHistory(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val limit = currentHistoryLimit()
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val existing = st.scriptHistory.firstOrNull { it.path == clean }
            val entry = existing ?: ScriptHistoryEntry(path = clean)
            val combined = if (limit <= 0 && existing == null) {
                st.scriptHistory
            } else {
                HistoryPersistence.trimScriptHistory(
                    listOf(entry) + st.scriptHistory.filterNot { it.path == clean },
                    limit.coerceAtMost(200)
                )
            }
            nextList = combined
            st.copy(scriptHistory = combined)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun removeScriptHistory(path: String) {
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val next = st.scriptHistory.filterNot { it.path == path }
            nextList = next
            st.copy(scriptHistory = next)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun toggleScriptPin(path: String) {
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val updated = st.scriptHistory.map { entry ->
                if (entry.path == path) entry.copy(pinned = !entry.pinned) else entry
            }.sortedByDescending { it.pinned }
            nextList = updated
            st.copy(scriptHistory = updated)
        }
        persistScriptHistoryAsync(nextList)
    }

    fun pinScriptHistory(path: String) = toggleScriptPin(path)

    fun updateScriptEntry(oldPath: String, newPath: String, note: String? = null) {
        val cleanNew = newPath.trim()
        if (cleanNew.isEmpty()) return
        var nextList: List<ScriptHistoryEntry> = emptyList()
        _ui.update { st ->
            val next = st.scriptHistory.map { entry ->
                if (entry.path == oldPath) {
                    entry.copy(path = cleanNew, note = note ?: entry.note)
                } else entry
            }
            nextList = next
            st.copy(
                scriptHistory = next,
                scriptPath = if (st.scriptPath == oldPath) cleanNew else st.scriptPath
            )
        }
        persistScriptHistoryAsync(nextList)
    }

    fun updateScriptHistory(oldPath: String, newPath: String) =
        updateScriptEntry(oldPath, newPath, null)

    /** 模块 2：诚实处理未内置 Lua 执行引擎的事实，绝不伪造执行成功 */
    fun runScript(path: String) {
        val clean = path.trim()
        if (clean.isNotEmpty()) {
            setSetting("script_path", clean)
            addOrTouchScriptHistory(clean)
            _ui.update { it.copy(scriptPath = clean) }
        }
        toast("当前版本暂未内置 Lua 执行引擎，仅支持脚本路径管理与编辑")
    }

    fun setting(id: String): String =
        repo?.let { SettingsSpecs.byId(id)?.let { s -> SettingsRepository.valueOf(_ui.value.settings, s) } }
            ?: _ui.value.settings[id].orEmpty()

    fun setSetting(id: String, value: String) {
        if (id == "language") {
            AppStrings.setLang(value)
        } else if (id == "history_limit") {
            val newLimit = (value.toIntOrNull() ?: 500).coerceIn(0, 100000)
            historyStore.setLimit(newLimit)
            persistSearchHistoryAsync()
        }
        enqueueSettingWrite(id, value)
        _ui.update { st ->
            val nextSettings = st.settings + (id to value)
            val nextPreset = if (id == "theme_preset") {
                ThemePreset.entries.firstOrNull { p -> p.name == value } ?: st.preset
            } else st.preset
            val nextDark = if (id == "dark_mode") {
                DarkMode.entries.firstOrNull { d -> d.name == value } ?: st.darkMode
            } else st.darkMode
            val nextDyn = if (id == "dynamic_color") (value == "1") else st.dynamicColor
            st.copy(
                settings = nextSettings,
                preset = nextPreset,
                darkMode = nextDark,
                dynamicColor = nextDyn,
                metrics = buildMetrics(nextSettings),
                history = if (id == "history_limit") historyStore.list() else st.history
            )
        }
        if (id == "stealth_rotation" || id == "stealth_ephemeral_fd") {
            applyStealthFromSettings()
        }
    }

    fun resetAppSettings() {
        setPreset(ThemePreset.ROSE)
        setDarkMode(DarkMode.DARK)
        setDynamicColor(false)
        resetCustomFont(silent = true)
        setSetting("font_scale", "0.88")
        setSetting("dialog_corner", "12")
        setSetting("bg_opacity", "0.96")
        setSetting("panel_scale", "1.00")
        setSetting("language", "0")
        setSetting("icons_size", "48")
        toast("已恢复默认设置")
    }

    fun setPreset(p: ThemePreset) = setSetting("theme_preset", p.name)
    fun setDarkMode(m: DarkMode) = setSetting("dark_mode", m.name)
    fun setDynamicColor(v: Boolean) = setSetting("dynamic_color", if (v) "1" else "0")

    fun importCustomFontFromUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.importFromUri(ctx, uri)
            }
            res.onSuccess { imported ->
                applyImportedFont(imported)
            }.onFailure { err ->
                toast(err.message ?: "导入字体失败")
            }
        }
    }

    fun importCustomFontFromFile(path: String) {
        val ctx = appContext ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.importFromFile(ctx, path)
            }
            res.onSuccess { imported ->
                applyImportedFont(imported)
            }.onFailure { err ->
                toast(err.message ?: "导入字体失败")
            }
        }
    }

    private fun applyImportedFont(imported: com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont) {
        var nextFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList()
        _ui.update { st ->
            val updated = (listOf(imported) + st.managedFonts.filterNot { it.path == imported.path })
            nextFonts = updated
            val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(updated)
            st.copy(
                settings = st.settings +
                    ("custom_font_path" to imported.path) +
                    ("custom_font_name" to imported.displayName) +
                    ("managed_fonts_json" to json),
                customFontPath = imported.path,
                customFontName = imported.displayName,
                managedFonts = updated
            )
        }
        val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(nextFonts)
        enqueueSettingWrite("custom_font_path", imported.path, immediate = true)
        enqueueSettingWrite("custom_font_name", imported.displayName, immediate = true)
        enqueueSettingWrite("managed_fonts_json", json, immediate = true)
        toast("已导入并应用字体：${imported.displayName}")
    }

    /** 切换字体：path 为空代表切换为“系统默认” */
    fun selectFont(path: String, displayName: String = "") {
        if (path.isBlank()) {
            enqueueSettingWrite("custom_font_path", "", immediate = true)
            enqueueSettingWrite("custom_font_name", "", immediate = true)
            _ui.update { st ->
                st.copy(
                    settings = st.settings + ("custom_font_path" to "") + ("custom_font_name" to ""),
                    customFontPath = "",
                    customFontName = ""
                )
            }
            toast("已切换为系统默认字体")
            return
        }
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            toast("字体文件不存在，已自动移除该记录")
            deleteManagedFont(path, silent = true)
            return
        }
        val resolvedName = displayName.ifBlank { file.name }
        enqueueSettingWrite("custom_font_path", path, immediate = true)
        enqueueSettingWrite("custom_font_name", resolvedName, immediate = true)
        _ui.update { st ->
            st.copy(
                settings = st.settings + ("custom_font_path" to path) + ("custom_font_name" to resolvedName),
                customFontPath = path,
                customFontName = resolvedName
            )
        }
        toast("已切换字体：$resolvedName")
    }

    /** 删除单个已导入的字体；若正在使用则自动回退到“系统默认” */
    fun deleteManagedFont(path: String, silent: Boolean = false) {
        val ctx = appContext
        if (ctx != null) {
            scope.launch(Dispatchers.IO) {
                com.littlecodesauce.ggnext.ui.theme.AppFontCache.deleteManagedFont(ctx, path)
            }
        }
        var nextFonts: List<com.littlecodesauce.ggnext.ui.theme.AppFontCache.ImportedFont> = emptyList()
        var wasActive = false
        _ui.update { st ->
            val updated = st.managedFonts.filterNot { it.path == path }
            nextFonts = updated
            wasActive = st.customFontPath == path
            val json = com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(updated)
            st.copy(
                settings = st.settings +
                    ("managed_fonts_json" to json) +
                    if (wasActive) mapOf("custom_font_path" to "", "custom_font_name" to "") else emptyMap(),
                customFontPath = if (wasActive) "" else st.customFontPath,
                customFontName = if (wasActive) "" else st.customFontName,
                managedFonts = updated
            )
        }
        enqueueSettingWrite("managed_fonts_json", com.littlecodesauce.ggnext.ui.theme.AppFontCache.encodeFontList(nextFonts), immediate = true)
        if (wasActive) {
            enqueueSettingWrite("custom_font_path", "", immediate = true)
            enqueueSettingWrite("custom_font_name", "", immediate = true)
        }
        if (!silent) {
            toast(if (wasActive) "已删除字体并恢复系统默认" else "已删除字体")
        }
    }

    fun resetCustomFont(silent: Boolean = false) {
        enqueueSettingWrite("custom_font_path", "", immediate = true)
        enqueueSettingWrite("custom_font_name", "", immediate = true)
        _ui.update { st ->
            st.copy(
                settings = st.settings + ("custom_font_path" to "") + ("custom_font_name" to ""),
                customFontPath = "",
                customFontName = ""
            )
        }
        if (!silent) {
            toast("已恢复系统默认字体")
        }
    }

    fun acceptDisclaimer() {
        _ui.update { it.copy(disclaimerAccepted = true) }
        setSetting("disclaimer_v1_accepted", "1")
    }

    fun setHomeSettingsOpen(open: Boolean) {
        _ui.update {
            it.copy(
                homeSettingsOpen = open,
                homeTab = if (open) HomeTab.SETTINGS else HomeTab.HOME
            )
        }
    }

    fun selectHomeTab(tab: HomeTab) {
        _ui.update {
            it.copy(
                homeTab = tab,
                homeSettingsOpen = tab == HomeTab.SETTINGS
            )
        }
    }

    fun setFloatingBallVisible(visible: Boolean) = _ui.update {
        it.copy(
            floatingBallVisible = visible,
            panelVisible = if (!visible) false else it.panelVisible,
            speedBarExpanded = if (!visible) false else it.speedBarExpanded
        )
    }

    private fun applyAutoPauseIfEnabled(panelOpening: Boolean) {
        val st = _ui.value
        if (st.settings["autopause"] != "1") return
        val pid = st.target?.pid ?: return
        if (pid <= 0) return
        val sig = if (panelOpening) "-STOP" else "-CONT"
        _ui.update { it.copy(processPaused = panelOpening) }
        scope.launch(Dispatchers.IO) {
            engine.setFreezePaused(panelOpening)
            Shell.runAs(
                "kill $sig $pid",
                asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
            )
        }
    }

    fun setPanelVisible(visible: Boolean) {
        val prev = _ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = visible,
                floatingBallVisible = if (visible) true else it.floatingBallVisible,
                speedBarExpanded = if (visible) false else it.speedBarExpanded
            )
        }
        if (prev != visible) {
            applyAutoPauseIfEnabled(visible)
        }
    }

    fun togglePanelVisible() {
        val next = !_ui.value.panelVisible
        _ui.update {
            it.copy(
                panelVisible = next,
                floatingBallVisible = true,
                speedBarExpanded = false,
                sheetStack = if (!next) emptyList() else it.sheetStack
            )
        }
        applyAutoPauseIfEnabled(next)
    }

    fun minimize() {
        val wasVisible = _ui.value.panelVisible
        _ui.update {
            it.copy(
                sheetStack = emptyList(),
                panelVisible = false,
                floatingBallVisible = true
            )
        }
        if (wasVisible) {
            applyAutoPauseIfEnabled(false)
        }
    }

    // ============ 悬浮球变速控制（ShadowHook + xDL Agent 真实变速） ============

    fun toggleSpeedBar() {
        _ui.update { st -> st.copy(speedBarExpanded = !st.speedBarExpanded) }
    }

    fun setSpeedBarExpanded(expanded: Boolean) = _ui.update {
        it.copy(speedBarExpanded = expanded)
    }

    fun setSpeedWrapEnabled(enabled: Boolean) {
        _ui.update { it.copy(speedWrapEnabled = enabled) }
    }

    fun stepSpeed(faster: Boolean) {
        val cur = _ui.value.speedMultiplier
        val idx = SPEED_STEPS.indexOfFirst { abs(it - cur) < 0.01f }.let {
            if (it >= 0) it else SPEED_STEPS.indexOfFirst { s -> s >= cur }.coerceAtLeast(0)
        }
        val nextIdx = (if (faster) idx + 1 else idx - 1).coerceIn(0, SPEED_STEPS.lastIndex)
        val nextSpeed = SPEED_STEPS[nextIdx]
        _ui.update { st -> st.copy(speedBarExpanded = true) }
        setSpeedMultiplier(nextSpeed)
    }

    fun setSpeedMultiplier(v: Float) {
        val clamped = v.coerceIn(0.01f, 100f)
        setSetting("speed_value", clamped.toString())
        _ui.update { it.copy(speedMultiplier = clamped) }
        val targetPkg = _ui.value.target?.packageName
        val wrapEnabled = _ui.value.speedWrapEnabled
        scope.launch {
            val (_, msg) = withContext(Dispatchers.IO) {
                engine.applySpeedMultiplier(clamped, targetPkg, enableWrapProperty = wrapEnabled)
            }
            toast(msg)
        }
    }

    fun resetSpeed() {
        setSpeedMultiplier(1.0f)
    }

    // ============ 进程 ============

    fun loadProcesses(app: Application) {
        scope.launch {
            _ui.update { it.copy(processLoading = true) }
            val list = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
            val defaultTarget = _ui.value.target ?: list.firstOrNull { !it.system } ?: list.firstOrNull()
            _ui.update { it.copy(processes = list, processLoading = false) }
            if (_ui.value.target == null && defaultTarget != null) {
                attach(defaultTarget, silent = true)
            }
        }
    }

    fun setProcessFilter(index: Int) {
        val clamped = index.coerceIn(0, 2)
        setSetting("process_filter", clamped.toString())
        _ui.update { st -> st.copy(processFilter = clamped) }
    }

    fun toggleProcessSortBySize() {
        val next = !_ui.value.processSortBySize
        setSetting("process_sort_by_size", if (next) "1" else "0")
        _ui.update { st -> st.copy(processSortBySize = next) }
    }

    fun toggleProcessPause() {
        val st = _ui.value
        val next = !st.processPaused
        val pid = st.target?.pid ?: 0
        if (pid > 0) {
            val sig = if (next) "-STOP" else "-CONT"
            scope.launch(Dispatchers.IO) {
                engine.setFreezePaused(next)
                Shell.runAs(
                    "kill $sig $pid",
                    asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
                )
            }
        }
        _ui.update { it.copy(processPaused = next) }
        toast(if (next) "已暂停目标进程 ${st.target?.label.orEmpty()}" else "已恢复目标进程 ${st.target?.label.orEmpty()}")
    }

    fun attach(proc: TargetProcess, silent: Boolean = false, forceReattach: Boolean = false) {
        val currentTarget = _ui.value.target
        if (!forceReattach && currentTarget?.pid == proc.pid && _ui.value.attachedOk) {
            if (!silent) toast("当前已附加到 ${proc.label}")
            closeAllSheets()
            return
        }
        hasFuzzySession = false
        saveListScrollPosition("search_list", 0, 0)
        scope.launch {
            attachMutex.withLock {
                val mode = _ui.value.workMode
                var resolvedProc = proc
                var ok = withContext(Dispatchers.IO) {
                    engine.attach(
                        targetPid = resolvedProc.pid,
                        targetX64 = resolvedProc.x64,
                        isRootMode = (mode == WorkMode.ROOT),
                        context = appContext
                    )
                }
                if (!ok && resolvedProc.packageName.isNotBlank()) {
                    val app = appContext
                    if (app != null) {
                        val freshList = withContext(Dispatchers.IO) { ProcessScanner.scan(app) }
                        _ui.update { it.copy(processes = freshList) }
                        val replacement = freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName && it.label == resolvedProc.label
                        } ?: freshList.firstOrNull {
                            it.packageName == resolvedProc.packageName
                        }
                        if (replacement != null && replacement.pid != resolvedProc.pid) {
                            resolvedProc = replacement
                            ok = withContext(Dispatchers.IO) {
                                engine.attach(
                                    targetPid = resolvedProc.pid,
                                    targetX64 = resolvedProc.x64,
                                    isRootMode = (mode == WorkMode.ROOT),
                                    context = appContext
                                )
                            }
                        }
                    }
                }
                val rawRegions = withContext(Dispatchers.IO) { engine.regions() }
                val regions = withContext(Dispatchers.IO) { aggregate(rawRegions) }
                val defaultBase = rawRegions.firstOrNull { it.code == "Xa" || it.code == "Cd" || it.code == "Jh" || it.code == "Ca" }?.start
                    ?: rawRegions.firstOrNull { it.readable }?.start
                    ?: 0L

                // 若持久化恢复的保存列表中存在已冻结项，重新附加后自动下发到底层 ValueLocker
                if (ok) {
                    applyStealthFromSettings()
                    val savedFrozen = _ui.value.saved.filter { it.freeze }
                    if (savedFrozen.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            savedFrozen.forEach { item ->
                                engine.setFreeze(
                                    address = item.address,
                                    flags = item.flags,
                                    value = item.value,
                                    enabled = true,
                                    freezeType = item.freezeType,
                                    freezeFrom = item.freezeFrom,
                                    freezeTo = item.freezeTo
                                )
                            }
                        }
                    }
                }

                if (currentTarget != null && currentTarget.pid != resolvedProc.pid) {
                    withContext(Dispatchers.IO) { engine.bpStopAll() }
                    bpPollingJob?.cancel()
                    bpPollingJob = null
                }
                _ui.update {
                    val pkgRegions = resolveRegionsForPackage(it.settings, resolvedProc.packageName)
                    it.copy(
                        target = resolvedProc,
                        processPaused = false,
                        attachedOk = ok,
                        regions = regions,
                        selectedRegions = pkgRegions,
                        memoryBase = if (defaultBase != 0L) defaultBase else it.memoryBase,
                        sheetStack = if (silent) it.sheetStack else emptyList(),
                        hintVisible = true,
                        rawResults = emptyList(),
                        results = emptyList(),
                        totalCount = 0,
                        searchSelection = SelectionState.EMPTY,
                        memorySelection = SelectionState.EMPTY,
                        memoryRows = emptyList(),
                        memoryDisasm = emptyMap(),
                        breakpoints = if (currentTarget?.pid != resolvedProc.pid) emptyList() else it.breakpoints,
                        selectedBpIds = if (currentTarget?.pid != resolvedProc.pid) emptySet() else it.selectedBpIds,
                        fuzzy = it.fuzzy.copy(active = false)
                    )
                }
                AppLog.add("attach", "${resolvedProc.label} pid=${resolvedProc.pid} ok=$ok regions=${rawRegions.size}")
                if (!silent) {
                    if (ok) {
                        toast("已附加到 ${resolvedProc.label}")
                    } else {
                        val detail = engine.lastAttachError.ifBlank { "无跨进程内存读写权限，请检查 Root 授权" }
                        toast("附加 ${resolvedProc.label} 失败：$detail")
                    }
                }
            }
        }
    }

    fun refreshLiveValues(manual: Boolean = false) {
        val st = _ui.value
        if (!st.attachedOk || st.target == null) {
            if (manual) toast("请先附加目标进程")
            return
        }
        if (st.busy) return
        if (liveRefreshRunning && !manual) return
        liveRefreshRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                val cur = _ui.value
                val liveResultMap: Map<Long, Long> = if (cur.results.isNotEmpty() && (manual || cur.tab == NextTab.SEARCH_LIST)) {
                    val scrollIdx = getListScrollPosition("search_list").first.coerceIn(0, (cur.results.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.results.size)
                    val slice = cur.results.subList(startIdx, endIdx)
                    engine.batchReadRawValues(slice.map { it.address to it.size })
                } else emptyMap()

                val liveSavedMap: Map<Long, Long> = if (cur.saved.isNotEmpty() && (manual || cur.tab == NextTab.SAVED_LIST)) {
                    val scrollIdx = getListScrollPosition("saved_list").first.coerceIn(0, (cur.saved.size - 1).coerceAtLeast(0))
                    val startIdx = (scrollIdx - 15).coerceAtLeast(0)
                    val endIdx = (scrollIdx + 65).coerceAtMost(cur.saved.size)
                    val slice = cur.saved.subList(startIdx, endIdx)
                    val dynamicOrUnfrozen = slice.filter { !it.freeze || it.freezeType != 0 }.map { it.address to it.size }
                    if (dynamicOrUnfrozen.isNotEmpty()) engine.batchReadRawValues(dynamicOrUnfrozen) else emptyMap()
                } else emptyMap()

                val pageBase = cur.memoryBase
                val memCount = cur.memoryRows.size
                val memBlock: ByteArray? = if (memCount > 0 && (manual || cur.tab == NextTab.MEMORY)) {
                    engine.readBlock(pageBase, memCount * 4)
                } else null

                if (liveResultMap.isEmpty() && liveSavedMap.isEmpty() && memBlock == null) {
                    if (manual) withContext(Dispatchers.Main) { toast("已刷新实时内存数据") }
                    return@launch
                }

                _ui.update { s ->
                    var nextResults = s.results
                    var nextRaw = s.rawResults
                    if (liveResultMap.isNotEmpty() && s.results.isNotEmpty()) {
                        var changedAny = false
                        val updatedRes = ArrayList<MemoryItem>(s.results.size)
                        for (item in s.results) {
                            // Bug 修复：手动编辑过的值不被后台实时刷新覆盖，避免改成 9999 后又变回旧值
                            if (item.manuallyEdited) { updatedRes.add(item); continue }
                            val freshVal = liveResultMap[item.address]
                            if (freshVal != null && freshVal != item.value) {
                                changedAny = true
                                updatedRes.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                            } else {
                                updatedRes.add(item)
                            }
                        }
                        if (changedAny) {
                            nextResults = updatedRes
                            if (s.rawResults === s.results) {
                                nextRaw = updatedRes
                            } else if (s.rawResults.size <= 5000) {
                                val updatedRaw = ArrayList<MemoryItem>(s.rawResults.size)
                                for (r in s.rawResults) {
                                    if (r.manuallyEdited) { updatedRaw.add(r); continue }
                                    val freshVal = liveResultMap[r.address]
                                    if (freshVal != null && freshVal != r.value) {
                                        updatedRaw.add(r.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                    } else {
                                        updatedRaw.add(r)
                                    }
                                }
                                nextRaw = updatedRaw
                            }
                        }
                    }

                    var nextSaved = s.saved
                    if (liveSavedMap.isNotEmpty() && s.saved.isNotEmpty()) {
                        var savedChanged = false
                        val updatedSaved = ArrayList<MemoryItem>(s.saved.size)
                        for (item in s.saved) {
                            if ((item.freeze && item.freezeType == 0) || item.manuallyEdited) {
                                updatedSaved.add(item)
                            } else {
                                val freshVal = liveSavedMap[item.address]
                                if (freshVal != null && freshVal != item.value) {
                                    savedChanged = true
                                    updatedSaved.add(item.copy(value = freshVal, pointer = engine.classifyPointer(freshVal)))
                                } else {
                                    updatedSaved.add(item)
                                }
                            }
                        }
                        if (savedChanged) nextSaved = updatedSaved
                    }

                    var nextMemRows = s.memoryRows
                    if (memBlock != null && s.memoryRows.size == memCount && s.memoryBase == pageBase) {
                        var memChanged = false
                        val updatedMem = ArrayList<MemoryItem>(memCount)
                        for (r in 0 until memCount) {
                            val oldItem = s.memoryRows[r]
                            if (oldItem.manuallyEdited) { updatedMem.add(oldItem); continue }
                            val off = r * 4
                            val v = if (off + 4 <= memBlock.size) {
                                (memBlock[off].toLong() and 0xFF) or
                                    ((memBlock[off + 1].toLong() and 0xFF) shl 8) or
                                    ((memBlock[off + 2].toLong() and 0xFF) shl 16) or
                                    ((memBlock[off + 3].toLong() and 0xFF) shl 24)
                            } else oldItem.value
                            if (v != oldItem.value) {
                                memChanged = true
                                updatedMem.add(oldItem.copy(value = v, previousValue = oldItem.value))
                            } else {
                                updatedMem.add(oldItem)
                            }
                        }
                        if (memChanged) nextMemRows = updatedMem
                    }

                    if (nextResults === s.results && nextRaw === s.rawResults && nextSaved === s.saved && nextMemRows === s.memoryRows) {
                        s
                    } else {
                        s.copy(
                            results = nextResults,
                            rawResults = nextRaw,
                            saved = nextSaved,
                            memoryRows = nextMemRows
                        )
                    }
                }
                if (manual) withContext(Dispatchers.Main) { toast("已刷新实时内存数据") }
            } finally {
                liveRefreshRunning = false
            }
        }
    }

    private fun makeProgressCallback(label: String, startTimeMs: Long): (Long, Long, Long) -> Unit = { doneBytes, totalBytes, foundSoFar ->
        val frac = if (totalBytes > 0L) (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)) / 1000.0
        val eta = if (frac > 0.03f && frac < 0.995f) {
            ((elapsedSec / frac) * (1.0 - frac)).toInt().coerceAtLeast(0)
        } else if (frac >= 0.995f) {
            0
        } else {
            -1
        }
        val scannedMb = doneBytes.toFloat() / (1024f * 1024f)
        val totalMb = totalBytes.toFloat() / (1024f * 1024f)
        _searchProgressState.value = SearchProgressUi(
            active = true,
            text = label,
            progress = frac,
            foundSoFar = foundSoFar,
            etaSeconds = eta,
            scannedMb = scannedMb,
            totalMb = totalMb
        )
    }

    /** 端到端扫描取消（模块 2）：立即下发 CANCEL 到 C++ 守护进程/JNI 及 JVM 回退 */
    fun cancelSearch() {
        engine.cancelScan()
        activeSearchJob?.cancel()
        activeSearchJob = null
        _searchProgressState.value = SearchProgressUi()
        _ui.update { it.copy(busy = false) }
        toast("已取消搜索")
    }

    private val regionNames = mapOf(
        "Ch" to "C++ heap", "Jh" to "Java heap", "Ca" to "C++ alloc", "Cd" to "C++ .data",
        "Cb" to "C++ .bss", "A" to "Anonymous", "S" to "Stack", "Xa" to "Code app",
        "Xs" to "Code system", "J" to "Java", "B" to "Bad", "Ps" to "PPSSPP",
        "As" to "Ashmem", "V" to "Video", "O" to "Other"
    )

    private fun aggregate(raw: List<MemoryRegion>): List<RegionRow> {
        val base = raw.groupBy { it.code }
        return RegionCatalog.ALL.map { rt ->
            val items = base[rt.code].orEmpty()
            RegionRow(
                code = rt.code,
                name = regionNames[rt.code] ?: rt.name,
                size = items.sumOf { it.size },
                count = items.size
            )
        }
    }

    fun toggleRegion(code: String) {
        var nextSet: Set<String> = emptySet()
        var pkgName: String? = null
        _ui.update { cur ->
            val updated = if (cur.selectedRegions.contains(code)) cur.selectedRegions - code else cur.selectedRegions + code
            nextSet = updated
            pkgName = cur.target?.packageName
            cur.copy(selectedRegions = updated)
        }
        persistSelectedRegions(nextSet, pkgName, immediate = false)
    }

    fun resetRegions() {
        val defaultSet = RegionCatalog.DEFAULT_SELECTED
        var pkgName: String? = null
        _ui.update { cur ->
            pkgName = cur.target?.packageName
            cur.copy(selectedRegions = defaultSet)
        }
        persistSelectedRegions(defaultSet, pkgName, immediate = true)
    }

    fun saveRegions() {
        val cur = _ui.value
        persistSelectedRegions(cur.selectedRegions, cur.target?.packageName, immediate = true)
        closeAllSheets()
        toast("已保存内存范围设置")
    }

    /**
     * 计算本次搜索从 C++ 层拉取到 Kotlin 列表的最大条目数：
     * C++ `MmapCandidateStore` 已完整保存全部数百万候选以供改善 (Refine)，
     * UI 层仅需按当前显示上限 `filter.maxShow`（开启过滤条件时适度放宽缓冲）拉取即可，
     * 避免每次搜索结束后通过管道传输 10 万条数据造成严重卡顿与堆内存膨胀。
     */
    private fun computeNativeFetchLimit(filter: FilterState): Int {
        if (!engine.supportsNativeScan) return MemoryEngine.MAX_CACHED_CANDIDATES
        val maxShow = filter.maxShow.coerceIn(1, MemoryEngine.MAX_CACHED_CANDIDATES)
        return if (filter.isAnyFilterActive) {
            maxOf(maxShow * 4, 4000).coerceAtMost(MemoryEngine.MAX_CACHED_CANDIDATES)
        } else {
            maxShow
        }
    }

    // ============ 精确 / 范围 / 联合 / 蒙版 / 指针 / 模糊搜索 ============

    fun updateSearch(f: (SearchInput) -> SearchInput) {
        val prev = _ui.value.search
        val next = f(prev)
        _ui.update { it.copy(search = next) }
        if (next.type != prev.type) setSetting("search_type", next.type.toString())
        if (next.pointerType != prev.pointerType) setSetting("pointer_type", next.pointerType.toString())
        if (next.offsetHex != prev.offsetHex) setSetting("pointer_offset_hex", if (next.offsetHex) "1" else "0")
    }

    fun updateFuzzy(f: (FuzzyState) -> FuzzyState) {
        val prev = _ui.value.fuzzy
        val next = f(prev)
        _ui.update { it.copy(fuzzy = next) }
        if (next.type != prev.type) setSetting("fuzzy_type", next.type.toString())
    }

    fun updateFilter(f: (FilterState) -> FilterState) = _ui.update { it.copy(filter = f(it.filter)) }

    /** 解析精确搜索可选地址掩码（默认全 F 代表禁用掩码过滤） */
    private fun parseOptionalAddressMask(rawMask: String): Pair<Long, Long> {
        val clean = rawMask.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H").uppercase()
        if (clean.isEmpty() || clean.all { it == 'F' } || clean == "0") return 0L to 0L
        var maskBits = 0L
        var patternBits = 0L
        for (ch in clean) {
            maskBits = maskBits shl 4
            patternBits = patternBits shl 4
            if (ch != '?' && ch != '*') {
                val digit = Character.digit(ch, 16)
                if (digit in 0..15) {
                    maskBits = maskBits or 0xFL
                    patternBits = patternBits or digit.toLong()
                }
            }
        }
        return maskBits to patternBits
    }

    fun runSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        val input = st.search.text.trim()
        if (input.isEmpty()) {
            toast("请输入搜索条件")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val parsed = try {
                NumberParser.parse(input, st.search.type, st.search.sign)
            } catch (e: NumberParser.ParseException) {
                toast(e.message ?: "输入有误")
                return@launch
            }
            val displayInput = if (parsed.kind == NumberParser.Kind.GROUP) {
                NumberParser.normalizeGroupExpression(input, parsed.ordered, parsed.area)
            } else input
            val startTime = System.currentTimeMillis()
            val label = if (newSearch) "新搜索: $displayInput" else "改善: $displayInput"
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST,
                    search = if (displayInput != it.search.text) it.search.copy(text = displayInput) else it.search
                )
            }
            val type = parsed.baseType and 0x7F
            val isRange = parsed.kind == NumberParser.Kind.RANGE
            val memFrom = when (st.search.rangeMode) {
                1 -> st.search.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.search.rangeMode) {
                1 -> st.search.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.search.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.search.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.search.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val (addrMask, addrPattern) = parseOptionalAddressMask(st.search.mask)
            val criteria = MemoryEngine.Criteria(
                type = type,
                value = parsed.numbers.firstOrNull() ?: 0L,
                from = parsed.numbers.firstOrNull() ?: 0L,
                to = parsed.to.firstOrNull() ?: 0L,
                isRange = isRange,
                notRange = parsed.notRange,
                sign = parsed.sign,
                xorKey = parsed.xorKey,
                xorOffset = parsed.xorOffset,
                orderedGroup = parsed.numbers,
                groupTo = parsed.to,
                groupOps = parsed.groupOps,
                ordered = parsed.ordered,
                groupTypes = parsed.types,
                area = parsed.area.coerceIn(2, 65536),
                memoryFrom = memFrom,
                memoryTo = memTo,
                limit = computeNativeFetchLimit(st.filter),
                rawExpression = displayInput,
                encryptedXor = st.search.encrypted,
                selectedRegions = st.selectedRegions,
                mask = addrMask,
                maskPattern = addrPattern,
                isText = (parsed.kind == NumberParser.Kind.TEXT),
                textBytes = parsed.textBytes,
                textUtf16 = parsed.textUtf16
            )
            val isRefine = !newSearch && (hasFuzzySession || st.rawResults.isNotEmpty() || st.totalCount > 0L)
            val previous = if (isRefine) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.search(criteria, previous, progressCb)
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            hasFuzzySession = false
            if (historyStore.add(displayInput)) {
                persistSearchHistoryAsync()
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    searchType = type,
                    history = historyStore.list(),
                    fuzzy = it.fuzzy.copy(active = false)
                )
            }
            toast("找到 ${result.totalFound} 条结果")
        }
    }

    /** 执行模糊搜索（支持 onlyNth 步进下推到 C++ 层） */
    fun runFuzzySearch(sign: Int, forceNew: Boolean = false) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val isInit = forceNew || (!hasFuzzySession && st.rawResults.isEmpty())
            if (isInit) {
                saveListScrollPosition("search_list", 0, 0)
            }
            val actionName = when (sign and ValueType.SIGN_MASK) {
                ValueType.SIGN_NOT_EQUAL -> "有变化"
                ValueType.SIGN_GREATER_EQ -> "增加了"
                ValueType.SIGN_LESS_EQ -> "减少了"
                else -> "无变化"
            }
            val startTime = System.currentTimeMillis()
            val label = if (isInit) "模糊初始采样中..." else "模糊筛选 ($actionName)..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val delta = st.fuzzy.deltaText.trim().toLongOrNull() ?: 0L
            val onlyNthVal = st.fuzzy.onlyNth.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val memFrom = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeFrom.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyBefore) (base - dist).coerceAtLeast(0L) else base
                }
                else -> 0L
            }
            val memTo = when (st.fuzzy.rangeMode) {
                1 -> st.fuzzy.rangeTo.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: Long.MAX_VALUE
                2 -> {
                    val base = st.fuzzy.nearbyAddress.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() ?: 0L
                    val dist = st.fuzzy.nearbyDistance.trim().removePrefix("0x").removeSuffix("h").toLongOrNull() ?: 500L
                    if (st.fuzzy.nearbyAfter) base + dist else base
                }
                else -> Long.MAX_VALUE
            }
            val prev = if (isInit) null else st.rawResults
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.fuzzySearch(
                    type = st.fuzzy.type,
                    sign = sign,
                    delta = delta,
                    onlyNth = onlyNthVal,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    memoryFrom = memFrom,
                    memoryTo = memTo,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            hasFuzzySession = result.totalFound > 0L || result.items.isNotEmpty()
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    fuzzy = it.fuzzy.copy(active = hasFuzzySession)
                )
            }
            toast(
                if (isInit) "已完成初始模糊采样，共 ${result.totalFound} 项"
                else "模糊筛选 $actionName：剩余 ${result.totalFound} 项"
            )
        }
    }

    /** 蒙版搜索 */
    fun runMaskSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removeSuffix("h").replace("?", "0")
        val rawMask = st.search.mask.trim().removePrefix("0x").removeSuffix("h")
        val addrVal = rawAddr.toULongOrNull(16)?.toLong() ?: rawAddr.toLongOrNull(16) ?: st.memoryBase
        val maskVal = rawMask.toULongOrNull(16)?.toLong() ?: -1L
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val label = "地址蒙版搜索中..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.maskSearch(
                    addressPattern = addrVal,
                    maskBits = maskVal,
                    type = st.search.type,
                    sign = st.search.sign,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            if (st.search.text.isNotBlank() && historyStore.add(st.search.text.trim())) {
                persistSearchHistoryAsync()
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    history = historyStore.list()
                )
            }
            toast("蒙版搜索找到 ${result.totalFound} 项")
        }
    }

    /** 指针搜索 */
    fun runPointerSearch(newSearch: Boolean) {
        val st = _ui.value
        if (st.target == null) {
            toast("请先选择进程")
            return
        }
        if (!st.attachedOk || !engine.attachedOk) {
            toast("请先成功附加目标进程（当前无内存读取权限）")
            return
        }
        if (newSearch) {
            hasFuzzySession = false
            engine.clearRemoved()
            saveListScrollPosition("search_list", 0, 0)
        }
        val rawAddr = st.search.text.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val targetAddr = rawAddr.toULongOrNull(16)?.toLong()
            ?: rawAddr.toLongOrNull(16)
            ?: st.contextItem?.address
            ?: st.editing?.address
            ?: st.memoryHighlightAddress
            ?: st.memoryBase
        val rawOff = st.search.offset.trim()
        val isExplicitHex = st.search.offsetHex || rawOff.startsWith("0x", true) || rawOff.endsWith("h", true)
        val cleanOff = rawOff.removePrefix("-").removePrefix("+").removePrefix("0x").removePrefix("0X").removeSuffix("h").removeSuffix("H")
        val maxOffset = (if (isExplicitHex) {
            cleanOff.toULongOrNull(16)?.toLong() ?: 0x200L
        } else {
            cleanOff.toLongOrNull() ?: cleanOff.toULongOrNull(16)?.toLong() ?: 512L
        }).coerceAtLeast(0L)
        val ptrType = if (st.search.pointerType == ValueType.DWORD || st.search.pointerType == ValueType.QWORD) {
            st.search.pointerType
        } else {
            if (st.target.x64) ValueType.QWORD else ValueType.DWORD
        }
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch {
            val startTime = System.currentTimeMillis()
            val label = "指针链搜索中..."
            _searchProgressState.value = SearchProgressUi(active = true, text = label)
            _ui.update {
                it.copy(
                    busy = true,
                    busyText = label,
                    sheetStack = emptyList(),
                    tab = NextTab.SEARCH_LIST
                )
            }
            val prev = if (!newSearch && st.rawResults.isNotEmpty()) st.rawResults else null
            val progressCb = makeProgressCallback(label, startTime)
            val activeFilter = _ui.value.filter
            val fetchLimit = computeNativeFetchLimit(activeFilter)
            val (result, filtered) = withContext(Dispatchers.IO) {
                val res = engine.pointerSearch(
                    targetAddress = targetAddr,
                    maxOffset = maxOffset,
                    type = ptrType,
                    previous = prev,
                    selectedRegions = st.selectedRegions,
                    limit = fetchLimit,
                    onProgress = progressCb
                )
                val flt = if (res.cancelled) emptyList() else filterList(res.items, activeFilter)
                res to flt
            }
            _searchProgressState.value = SearchProgressUi()
            if (result.cancelled) {
                _ui.update { it.copy(busy = false) }
                return@launch
            }
            _ui.update {
                it.copy(
                    rawResults = result.items,
                    results = filtered,
                    totalCount = result.totalFound,
                    busy = false,
                    hintVisible = filtered.isEmpty(),
                    searchSelection = SelectionState.EMPTY,
                    searchType = ptrType
                )
            }
            toast("指针搜索找到 ${result.totalFound} 条结果")
        }
    }

    // ============ 结果过滤/筛选 ============

    fun applyFilter(newFilter: FilterState) {
        val st = _ui.value
        val maxShow = newFilter.maxShow.coerceIn(1, 100000)
        setSetting("filter_max_show", maxShow.toString())
        val normalized = newFilter.copy(maxShow = maxShow)

        var mask = st.valueMask
        fun setBit(bit: Int, on: Boolean) {
            mask = if (on) (mask or bit) else (mask and bit.inv())
        }
        setBit(Formatter.F_HEX, normalized.formatHex)
        setBit(Formatter.F_RHEX, normalized.formatRHex)
        setBit(Formatter.F_STRING, normalized.formatString)
        setBit(Formatter.F_JAVA, normalized.formatUtf16)
        if (mask == 0) mask = SettingsSpecs.DEFAULT_FORMAT_MASK
        repo?.let { r -> scope.launch(Dispatchers.IO) { r.setValueFormatMask(mask) } }

        val desiredCap = computeNativeFetchLimit(normalized)
        val needExpandFromNative = engine.supportsNativeScan &&
            st.totalCount > st.rawResults.size.toLong() &&
            desiredCap > st.rawResults.size

        if (!needExpandFromNative) {
            val filtered = filterList(st.rawResults, normalized)
            val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
            _ui.update {
                it.copy(
                    filter = normalized,
                    results = filtered,
                    searchSelection = it.searchSelection.prune(validKeys),
                    valueMask = mask,
                    sheetStack = emptyList()
                )
            }
            toast("已应用筛选")
            return
        }

        _ui.update {
            it.copy(
                filter = normalized,
                valueMask = mask,
                sheetStack = emptyList(),
                isBatchBusy = true
            )
        }
        scope.launch {
            val (expandedRaw, filtered) = withContext(Dispatchers.IO) {
                val fetched = engine.fetchStoredCandidates(
                    limit = desiredCap,
                    defaultType = st.searchType,
                    useCandidateDt = true
                )
                val base = if (fetched.isNotEmpty()) fetched else st.rawResults
                base to filterList(base, normalized)
            }
            val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
            _ui.update {
                it.copy(
                    rawResults = expandedRaw,
                    results = filtered,
                    searchSelection = it.searchSelection.prune(validKeys),
                    isBatchBusy = false
                )
            }
            toast("已应用筛选")
        }
    }

    fun resetFilter() {
        val def = FilterState()
        setSetting("filter_max_show", def.maxShow.toString())
        repo?.let { r -> scope.launch(Dispatchers.IO) { r.setValueFormatMask(SettingsSpecs.DEFAULT_FORMAT_MASK) } }
        val filtered = filterList(_ui.value.rawResults, def)
        val validKeys = filtered.mapTo(HashSet(filtered.size * 2)) { SelectionState.itemKey(it) }
        _ui.update {
            it.copy(
                filter = def,
                results = filtered,
                searchSelection = it.searchSelection.prune(validKeys),
                valueMask = SettingsSpecs.DEFAULT_FORMAT_MASK
            )
        }
        toast("已重置筛选条件")
    }

    private fun filterList(source: List<MemoryItem>, f: FilterState): List<MemoryItem> {
        if (source.isEmpty()) return emptyList()
        val addrMin = if (f.addrGreatEnabled) f.addrGreat.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val addrMax = if (f.addrLessEnabled) f.addrLess.trim().removePrefix("0x").removeSuffix("h").toULongOrNull(16)?.toLong() else null
        val valMin = if (f.valGreatEnabled) f.valGreat.trim().toLongOrNull() else null
        val valMax = if (f.valLessEnabled) f.valLess.trim().toLongOrNull() else null
        val fracTarget = if (f.fractionalEnabled) f.fractional.trim().removePrefix(".") else null

        val seenKeys = HashSet<Long>(minOf(source.size, f.maxShow) * 2)
        val out = ArrayList<MemoryItem>(minOf(source.size, f.maxShow))
        for (item in source) {
            if (!seenKeys.add(SelectionState.itemKey(item))) continue
            if (addrMin != null && java.lang.Long.compareUnsigned(item.address, addrMin) < 0) continue
            if (addrMax != null && java.lang.Long.compareUnsigned(item.address, addrMax) > 0) continue
            val signedVal = Formatter.signed(item.value, item.size)
            if (valMin != null && signedVal < valMin) continue
            if (valMax != null && signedVal > valMax) continue
            if (f.typeEnabled && f.types.isNotEmpty() && (item.flags and 0x7F) !in f.types) continue
            if (f.pointerEnabled && f.pointers.isNotEmpty()) {
                val pCode = if (item.pointer == 0) 4 else item.pointer
                if (pCode !in f.pointers) continue
            }
            if (fracTarget != null && fracTarget.isNotEmpty()) {
                val floatStr = when (item.flags and 0x7F) {
                    ValueType.FLOAT -> Formatter.floatText(item.value)
                    ValueType.DOUBLE -> Formatter.doubleText(item.value)
                    else -> "0.0"
                }
                val afterDot = floatStr.substringAfter('.', "0")
                val matchesFrac = afterDot.startsWith(fracTarget)
                if (f.fractionalNotEqual && matchesFrac) continue
                if (!f.fractionalNotEqual && !matchesFrac) continue
            }
            out.add(item)
            if (out.size >= f.maxShow.coerceAtLeast(1)) break
        }
        return out
    }

    fun clearResults() {
        hasFuzzySession = false
        saveListScrollPosition("search_list", 0, 0)
        scope.launch(Dispatchers.IO) { engine.clearSession() }
        _ui.update {
            it.copy(
                rawResults = emptyList(),
                results = emptyList(),
                totalCount = 0,
                searchSelection = SelectionState.EMPTY,
                hintVisible = true,
                fuzzy = it.fuzzy.copy(active = false)
            )
        }
    }

    // ============ O(1) 选择操作（模块 4：零列表拷贝，常数时间） ============

    private fun sameItemKey(a: MemoryItem, b: MemoryItem): Boolean =
        a.address == b.address && (a.flags and 0x7F) == (b.flags and 0x7F)

    fun toggle(item: MemoryItem) {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> s.copy(savedSelection = s.savedSelection.toggle(SelectionState.itemKey(item)))
                NextTab.MEMORY -> s.copy(memorySelection = s.memorySelection.toggle(SelectionState.memoryRowKey(item)))
                NextTab.SEARCH_LIST -> s.copy(searchSelection = s.searchSelection.toggle(SelectionState.itemKey(item)))
                NextTab.SETTINGS, NextTab.DEBUG, NextTab.AI -> s
            }
        }
    }

    fun selectAll() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> if (s.saved.isEmpty()) s else s.copy(savedSelection = s.savedSelection.selectAll())
                NextTab.MEMORY -> if (s.memoryRows.isEmpty()) s else s.copy(memorySelection = s.memorySelection.selectAll())
                NextTab.SEARCH_LIST -> if (s.results.isEmpty()) s else s.copy(searchSelection = s.searchSelection.selectAll())
                NextTab.DEBUG -> s.copy(selectedBpIds = s.breakpoints.map { it.id }.toSet())
                NextTab.SETTINGS, NextTab.AI -> s
            }
        }
    }

    fun invertSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> if (s.saved.isEmpty()) s else s.copy(savedSelection = s.savedSelection.invert())
                NextTab.MEMORY -> if (s.memoryRows.isEmpty()) s else s.copy(memorySelection = s.memorySelection.invert())
                NextTab.SEARCH_LIST -> if (s.results.isEmpty()) s else s.copy(searchSelection = s.searchSelection.invert())
                NextTab.DEBUG -> {
                    val all = s.breakpoints.map { it.id }.toSet()
                    s.copy(selectedBpIds = all - s.selectedBpIds)
                }
                NextTab.SETTINGS, NextTab.AI -> s
            }
        }
    }

    fun clearSelection() {
        _ui.update { s ->
            when (s.tab) {
                NextTab.SAVED_LIST -> s.copy(savedSelection = SelectionState.EMPTY)
                NextTab.MEMORY -> s.copy(memorySelection = SelectionState.EMPTY)
                NextTab.SEARCH_LIST -> s.copy(searchSelection = SelectionState.EMPTY)
                NextTab.DEBUG -> s.copy(selectedBpIds = emptySet())
                NextTab.SETTINGS, NextTab.AI -> s
            }
        }
    }

    fun checkedItems(): List<MemoryItem> {
        val st = _ui.value
        return when (st.tab) {
            NextTab.SAVED_LIST -> st.savedSelection.resolveSelected(st.saved, SelectionState::itemKey)
            NextTab.MEMORY -> st.memorySelection.resolveSelected(st.memoryRows, SelectionState::memoryRowKey)
            NextTab.SEARCH_LIST -> st.searchSelection.resolveSelected(st.results, SelectionState::itemKey)
            NextTab.SETTINGS, NextTab.DEBUG, NextTab.AI -> emptyList()
        }
    }

    fun itemsToEdit(): List<MemoryItem> {
        val st = _ui.value
        if (st.editSingleOnly && st.editing != null) {
            return listOf(st.editing)
        }
        val checked = checkedItems()
        if (checked.isNotEmpty()) return checked
        st.editing?.let { return listOf(it) }
        return when (st.tab) {
            NextTab.SAVED_LIST -> st.saved
            NextTab.MEMORY -> st.memoryRows
            NextTab.SEARCH_LIST -> st.results
            NextTab.SETTINGS, NextTab.DEBUG, NextTab.AI -> emptyList()
        }
    }

    // ============ 保存 / 删除 / 冻结 / 恢复（全部异步 IO） ============

    fun saveChecked() {
        val selected = checkedItems()
        if (selected.isEmpty()) {
            toast("无项目被选中")
            return
        }
        scope.launch(Dispatchers.Default) {
            val cur = _ui.value
            val map = LinkedHashMap<Long, MemoryItem>((cur.saved.size + selected.size) * 4 / 3 + 16)
            for (existing in cur.saved) {
                map[SelectionState.itemKey(existing)] = existing
            }
            for (item in selected) {
                val k = SelectionState.itemKey(item)
                val prev = map[k]
                map[k] = if (prev != null) {
                    item.copy(checked = false, freeze = prev.freeze, freezeType = prev.freezeType, freezeFrom = prev.freezeFrom, freezeTo = prev.freezeTo, note = item.note.ifEmpty { prev.note })
                } else {
                    item.copy(checked = false)
                }
            }
            val mergedList = ArrayList<MemoryItem>(map.values)
            syncFrozenSnapshot(mergedList)
            persistSavedListAsync(mergedList)
            _ui.update { s -> s.copy(saved = mergedList) }
            toast("已保存 ${selected.size} 项到保存列表")
        }
    }

    fun deleteSaved(item: MemoryItem) {
        scope.launch(Dispatchers.IO) {
            engine.setFreeze(item.address, item.flags, item.value, false)
        }
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { st ->
            val nextSaved = st.saved.filterNot { sameItemKey(it, item) }
            nextSavedSnapshot = nextSaved
            syncFrozenSnapshot(nextSaved)
            val validKeys = nextSaved.mapTo(HashSet()) { SelectionState.itemKey(it) }
            st.copy(saved = nextSaved, savedSelection = st.savedSelection.prune(validKeys))
        }
        persistSavedListAsync(nextSavedSnapshot)
    }

    fun deleteResultItem(item: MemoryItem) {
        scope.launch(Dispatchers.IO) {
            engine.markRemoved(listOf(item.address))
        }
        _ui.update { st ->
            val nextRaw = st.rawResults.filterNot { sameItemKey(it, item) }
            val nextRes = filterList(nextRaw, st.filter)
            val nextTotal = (st.totalCount - 1L).coerceAtLeast(nextRaw.size.toLong())
            val validKeys = nextRes.mapTo(HashSet()) { SelectionState.itemKey(it) }
            st.copy(
                rawResults = nextRaw,
                results = nextRes,
                totalCount = nextTotal,
                searchSelection = st.searchSelection.prune(validKeys),
                hintVisible = nextRes.isEmpty()
            )
        }
    }

    fun toggleFreeze(item: MemoryItem) {
        val nextFreeze = !item.freeze
        var nextSavedSnapshot: List<MemoryItem> = emptyList()
        _ui.update { st ->
            val nextSaved = st.saved.map { if (sameItemKey(it, item)) it.copy(freeze = nextFreeze) else it }
            nextSavedSnapshot = nextSaved
            syncFrozenSnapshot(nextSaved)
            st.copy(saved = nextSaved)
        }
        persistSavedListAsync(nextSavedSnapshot)
        scope.launch(Dispatchers.IO) {
            engine.setFreeze(
                address = item.address,
                flags = item.flags,
                value = item.value,
                enabled = nextFreeze,
                freezeType = item.freezeType,
                freezeFrom = item.freezeFrom,
                freezeTo = item.freezeTo
            )
        }
    }

    fun revertChecked() {
        val targets = checkedItems()
        if (targets.isEmpty()) {
            toast("无项目被选中")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            withContext(Dispatchers.IO) {
                targets.forEach { engine.write(it.address, it.size, it.previousValue) }
            }
            val addrSet = targets.mapTo(HashSet()) { it.address }
            var nextSavedSnapshot: List<MemoryItem> = emptyList()
            _ui.update { st ->
                val nextSaved = st.saved.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
                nextSavedSnapshot = nextSaved
                syncFrozenSnapshot(nextSaved)
                st.copy(
                    isBatchBusy = false,
                    rawResults = st.rawResults.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it },
                    results = st.results.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it },
                    saved = nextSaved,
                    memoryRows = st.memoryRows.map { if (it.address in addrSet) it.copy(value = it.previousValue, manuallyEdited = false) else it }
                )
            }
            persistSavedListAsync(nextSavedSnapshot)
            toast("已恢复 ${targets.size} 项为先前数值")
        }
    }

    fun loadCheckedAsResults() {
        val items = checkedItems()
        if (items.isEmpty()) {
            toast("请先勾选要载入为搜索结果的项目")
            return
        }
        val clean = items.map { it.copy(checked = false) }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = clean,
                results = clean,
                totalCount = clean.size.toLong(),
                searchSelection = SelectionState.EMPTY,
                hintVisible = false,
                tab = NextTab.SEARCH_LIST
            )
        }
        toast("已将 ${clean.size} 项载入为搜索结果")
    }

    fun loadChangedAsResults() {
        val changed = _ui.value.rawResults.filter { it.value != it.previousValue }
        if (changed.isEmpty()) {
            toast("未发现数值发生变化的项目")
            return
        }
        saveListScrollPosition("search_list", 0, 0)
        _ui.update {
            it.copy(
                rawResults = changed,
                results = filterList(changed, it.filter),
                totalCount = changed.size.toLong(),
                searchSelection = SelectionState.EMPTY
            )
        }
        toast("已保留 ${changed.size} 个有变化的结果")
    }

    fun removeChecked(all: Boolean = false, revertFirst: Boolean = false) {
        val stNow = _ui.value
        val targets = if (all) {
            when (stNow.tab) {
                NextTab.SAVED_LIST -> stNow.saved
                NextTab.MEMORY -> stNow.memoryRows
                else -> stNow.results
            }
        } else {
            checkedItems()
        }
        scope.launch {
            if (revertFirst && targets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    targets.forEach { engine.write(it.address, it.size, it.previousValue) }
                }
            }
            if (all) {
                when (stNow.tab) {
                    NextTab.SAVED_LIST -> {
                        val toUnlock = _ui.value.saved.filter { it.freeze }
                        withContext(Dispatchers.IO) {
                            toUnlock.forEach { engine.setFreeze(it.address, it.flags, it.value, false) }
                        }
                        syncFrozenSnapshot(emptyList())
                        persistSavedListAsync(emptyList())
                        _ui.update { it.copy(saved = emptyList(), savedSelection = SelectionState.EMPTY, sheetStack = emptyList()) }
                    }
                    NextTab.MEMORY -> _ui.update { it.copy(memoryRows = emptyList(), memorySelection = SelectionState.EMPTY, sheetStack = emptyList()) }
                    else -> {
                        clearResults()
                        closeAllSheets()
                    }
                }
                return@launch
            }

            when (stNow.tab) {
                NextTab.SAVED_LIST -> {
                    val removeKeys = targets.mapTo(HashSet()) { SelectionState.itemKey(it) }
                    val toUnlock = targets.filter { it.freeze }
                    withContext(Dispatchers.IO) {
                        toUnlock.forEach { engine.setFreeze(it.address, it.flags, it.value, false) }
                    }
                    var nextSavedSnapshot: List<MemoryItem> = emptyList()
                    _ui.update { s ->
                        val nextSaved = s.saved.filterNot { SelectionState.itemKey(it) in removeKeys }
                        nextSavedSnapshot = nextSaved
                        syncFrozenSnapshot(nextSaved)
                        s.copy(saved = nextSaved, savedSelection = SelectionState.EMPTY, sheetStack = emptyList())
                    }
                    persistSavedListAsync(nextSavedSnapshot)
                }
                NextTab.MEMORY -> {
                    val removeAddrs = targets.mapTo(HashSet()) { it.address }
                    _ui.update { s ->
                        s.copy(
                            memoryRows = s.memoryRows.filterNot { it.address in removeAddrs },
                            memorySelection = SelectionState.EMPTY,
                            sheetStack = emptyList()
                        )
                    }
                }
                else -> {
                    val removedAddrs = targets.mapTo(HashSet()) { it.address }
                    val removeKeys = targets.mapTo(HashSet()) { SelectionState.itemKey(it) }
                    if (removedAddrs.isNotEmpty()) {
                        withContext(Dispatchers.IO) { engine.markRemoved(removedAddrs) }
                    }
                    _ui.update { s ->
                        val nextRaw = s.rawResults.filterNot { SelectionState.itemKey(it) in removeKeys || it.address in removedAddrs }
                        val nextRes = filterList(nextRaw, s.filter)
                        val nextTotal = (s.totalCount - removedAddrs.size).coerceAtLeast(nextRaw.size.toLong())
                        s.copy(
                            rawResults = nextRaw,
                            results = nextRes,
                            totalCount = nextTotal,
                            searchSelection = SelectionState.EMPTY,
                            hintVisible = nextRes.isEmpty(),
                            sheetStack = emptyList()
                        )
                    }
                }
            }
        }
    }

    /**
     * 模块 1 & 模块 2：全异步批量编辑：
     * - 所有 `engine.write` / `engine.setFreeze` 均在 `Dispatchers.IO` 执行，零主线程阻塞；
     * - 完整支持 4 种冻结模式 (`0=正常`, `1=递增`, `2=递减`, `3=范围钳制` + `freezeFrom`/`freezeTo`)；
     * - 若条目由加密/XOR 搜索命中 (`xorKey`/`xorOffset`/`encryptedAuto`)，自动按原编码方式重编码写回！
     */
    fun batchEdit(
        targets: List<MemoryItem>,
        rawInput: String,
        targetType: Int,
        rawStep: String = "",
        addNotReplace: Boolean = false,
        freeze: Boolean = false,
        freezeType: Int = 0,
        freezeFromRaw: String = "",
        freezeToRaw: String = "",
        customNote: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        if (targets.isEmpty()) {
            toast("没有可修改的项目")
            onComplete?.invoke(false)
            return false
        }
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            toast("请输入修改数值")
            onComplete?.invoke(false)
            return false
        }
        val ggAsm = AsmEngineManager.parseGgAsmPrefix(trimmed)
        if (ggAsm != null) {
            val (asmArch, asmBody) = ggAsm
            if (asmBody.isBlank()) {
                toast("请输入汇编指令，例如 ${asmArch.ggPrefix} NOP")
                onComplete?.invoke(false)
                return false
            }
            scope.launch {
                _ui.update { it.copy(isBatchBusy = true) }
                var writeError: String? = null
                val updatedByAddr = withContext(Dispatchers.IO) {
                    val map = HashMap<Long, MemoryItem>(targets.size * 2)
                    for (item in targets) {
                        val res = AsmEngineManager.assemble(asmArch, item.address, asmBody)
                        if (res.isFailure) {
                            writeError = res.exceptionOrNull()?.message ?: "汇编失败"
                            break
                        }
                        val bytes = res.getOrThrow()
                        if (bytes.isEmpty()) continue
                        engine.writeBlock(item.address, bytes)
                        val newWord = engine.read(item.address, item.size)
                        map[item.address] = item.copy(
                            value = newWord,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (!customNote.isNullOrBlank()) customNote else item.note
                        )
                    }
                    map
                }
                if (writeError != null) {
                    _ui.update { it.copy(isBatchBusy = false) }
                    toast("汇编写入失败：$writeError")
                    onComplete?.invoke(false)
                    return@launch
                }
                if (historyStore.add(trimmed)) persistSearchHistoryAsync()
                _ui.update { st ->
                    st.copy(
                        isBatchBusy = false,
                        rawResults = st.rawResults.map { updatedByAddr[it.address] ?: it },
                        results = st.results.map { updatedByAddr[it.address] ?: it },
                        saved = st.saved.map { updatedByAddr[it.address] ?: it },
                        memoryRows = st.memoryRows.map { updatedByAddr[it.address] ?: it },
                        lastBatchEditValue = trimmed,
                        history = historyStore.list()
                    )
                }
                refreshMemoryDisasmAsync()
                toast("已写入 ${asmArch.shortCode} 汇编至 ${targets.size} 处地址")
                onComplete?.invoke(true)
            }
            return true
        }

        val cleanType = if ((targetType and 0x7F) == 0 || targetType == ValueType.AUTO) {
            targets.first().flags and 0x7F
        } else {
            targetType and 0x7F
        }
        val stepDouble = rawStep.trim().toDoubleOrNull() ?: 0.0
        val stepLong = rawStep.trim().toLongOrNull() ?: 0L

        val isUtf8Text = trimmed.startsWith(":") || trimmed.startsWith("'")
        val isUtf16Text = trimmed.startsWith(";") || trimmed.startsWith("\"")
        val isHexStream = (trimmed.startsWith("h ", true) || trimmed.startsWith("Q ", true)) && trimmed.length > 2

        val parsedParts: List<Long>
        val parsedFreezeFrom: Long
        val parsedFreezeTo: Long
        try {
            parsedParts = if (!isUtf8Text && !isUtf16Text && !isHexStream) {
                val parts = if (trimmed.contains(';')) {
                    trimmed.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    listOf(trimmed)
                }
                if (parts.isEmpty()) throw NumberParser.ParseException("修改值无效")
                parts.map { NumberParser.parseNumber(it, cleanType).first }
            } else emptyList()

            parsedFreezeFrom = if (freeze && freezeType == 3 && freezeFromRaw.isNotBlank()) {
                NumberParser.parseNumber(freezeFromRaw.trim(), cleanType).first
            } else 0L
            parsedFreezeTo = if (freeze && freezeType == 3 && freezeToRaw.isNotBlank()) {
                NumberParser.parseNumber(freezeToRaw.trim(), cleanType).first
            } else 0L
        } catch (e: NumberParser.ParseException) {
            toast(e.message ?: "输入格式错误")
            onComplete?.invoke(false)
            return false
        }

        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updatedByAddr = withContext(Dispatchers.IO) {
                val map = HashMap<Long, MemoryItem>(targets.size * 4 / 3 + 16)
                val itemSize = ValueType.sizeOf(cleanType)

                fun reencodeIfEncrypted(item: MemoryItem, plainBits: Long): Long {
                    return when {
                        item.xorOffset != 0 -> {
                            val key = engine.read(item.address + item.xorOffset, itemSize)
                            plainBits xor key
                        }
                        item.xorKey != 0L -> plainBits xor item.xorKey
                        item.encryptedAuto -> {
                            val key = engine.read(item.address + itemSize, itemSize)
                            plainBits xor key
                        }
                        else -> plainBits
                    }
                }

                if (isUtf8Text || isUtf16Text) {
                    val body = trimmed.substring(1).trimEnd('\'', '"')
                    val bytes = if (isUtf16Text) body.toByteArray(Charsets.UTF_16LE) else body.toByteArray(Charsets.UTF_8)
                    targets.forEachIndexed { idx, item ->
                        val bValue = if (isUtf16Text && cleanType == ValueType.WORD) {
                            val b0 = bytes.getOrNull(idx * 2)?.toLong()?.and(0xFF) ?: 0L
                            val b1 = bytes.getOrNull(idx * 2 + 1)?.toLong()?.and(0xFF) ?: 0L
                            b0 or (b1 shl 8)
                        } else {
                            bytes.getOrNull(idx)?.toLong()?.and(0xFF) ?: 0L
                        }
                        val writeOk = engine.write(item.address, itemSize, bValue)
                        val effectiveVal = if (writeOk) bValue else engine.read(item.address, itemSize)
                        map[item.address] = item.copy(
                            value = effectiveVal,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                        )
                    }
                } else if (isHexStream) {
                    val hexTokens = trimmed.substring(2).trim()
                        .split(Regex("\\s+"))
                        .mapNotNull { tok -> tok.toIntOrNull(16)?.toByte() }
                    targets.forEachIndexed { idx, item ->
                        val v = hexTokens.getOrNull(idx)?.toLong()?.and(0xFF) ?: item.value
                        val writeOk = engine.write(item.address, itemSize, v)
                        val effectiveVal = if (writeOk) v else engine.read(item.address, itemSize)
                        map[item.address] = item.copy(
                            value = effectiveVal,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                        )
                    }
                } else {
                    targets.forEachIndexed { idx, item ->
                        val parsedBase = parsedParts[idx % parsedParts.size]
                        val plainCur = when {
                            item.xorOffset != 0 -> item.value xor engine.read(item.address + item.xorOffset, itemSize)
                            item.xorKey != 0L -> item.value xor item.xorKey
                            item.encryptedAuto -> item.value xor engine.read(item.address + itemSize, itemSize)
                            else -> item.value
                        }
                        val plainNextBits = when (cleanType) {
                            ValueType.FLOAT -> {
                                val baseF = Float.fromBits(parsedBase.toInt())
                                val curF = Float.fromBits(plainCur.toInt())
                                val nextF = (if (addNotReplace) curF + baseF else baseF) + (idx * stepDouble).toFloat()
                                nextF.toRawBits().toLong() and 0xFFFFFFFFL
                            }
                            ValueType.DOUBLE -> {
                                val baseD = Double.fromBits(parsedBase)
                                val curD = Double.fromBits(plainCur)
                                val nextD = (if (addNotReplace) curD + baseD else baseD) + (idx * stepDouble)
                                nextD.toRawBits()
                            }
                            else -> {
                                val curSigned = Formatter.signed(plainCur, itemSize)
                                val baseSigned = Formatter.signed(parsedBase, itemSize)
                                (if (addNotReplace) curSigned + baseSigned else baseSigned) + (idx * stepLong)
                            }
                        }
                        val encodedBits = reencodeIfEncrypted(item, plainNextBits)
                        val writeOk = engine.write(item.address, itemSize, encodedBits)
                        // Bug 修复：写入失败时以内存实际值为准，避免 UI 显示目标值随后被刷新覆盖
                        val effectiveBits = if (writeOk) encodedBits else engine.read(item.address, itemSize)
                        val edited = item.copy(
                            value = effectiveBits,
                            flags = cleanType,
                            freeze = if (freeze) true else item.freeze,
                            freezeType = if (freeze) freezeType else item.freezeType,
                            freezeFrom = if (freeze && freezeType == 3) parsedFreezeFrom else item.freezeFrom,
                            freezeTo = if (freeze && freezeType == 3) parsedFreezeTo else item.freezeTo,
                            previousValue = item.value,
                            manuallyEdited = true,
                            note = if (customNote != null && customNote.isNotBlank()) customNote else item.note
                        )
                        if (edited.freeze) {
                            engine.setFreeze(
                                address = edited.address,
                                flags = edited.flags,
                                value = edited.value,
                                enabled = true,
                                freezeType = edited.freezeType,
                                freezeFrom = edited.freezeFrom,
                                freezeTo = edited.freezeTo
                            )
                        }
                        map[item.address] = edited
                    }
                }
                map
            }

            if (historyStore.add(trimmed)) {
                persistSearchHistoryAsync()
            }

            var nextSavedSnapshot: List<MemoryItem> = emptyList()
            _ui.update { st ->
                val savedByAddr = LinkedHashMap<Long, MemoryItem>((st.saved.size + updatedByAddr.size) * 4 / 3 + 16)
                for (sItem in st.saved) {
                    savedByAddr[sItem.address] = sItem
                }
                updatedByAddr.values.forEach { edited ->
                    val existing = savedByAddr[edited.address]
                    if (existing != null) {
                        savedByAddr[edited.address] = edited
                    } else if (freeze || (customNote != null && customNote.isNotBlank())) {
                        savedByAddr[edited.address] = edited
                    }
                }
                val nextSaved = ArrayList<MemoryItem>(savedByAddr.values)
                nextSavedSnapshot = nextSaved
                syncFrozenSnapshot(nextSaved)
                st.copy(
                    isBatchBusy = false,
                    rawResults = st.rawResults.map { updatedByAddr[it.address] ?: it },
                    results = st.results.map { updatedByAddr[it.address] ?: it },
                    saved = nextSaved,
                    memoryRows = st.memoryRows.map { updatedByAddr[it.address] ?: it },
                    lastBatchEditValue = trimmed,
                    lastBatchEditStep = rawStep.trim(),
                    history = historyStore.list()
                )
            }
            persistSavedListAsync(nextSavedSnapshot)
            toast(if (targets.size == 1) "已修改地址 ${targets.first().cachedAddressHex}" else "已批量修改 ${targets.size} 项")
            onComplete?.invoke(true)
        }
        return true
    }

    fun editValue(item: MemoryItem, raw: String): Boolean =
        batchEdit(listOf(item), raw, item.flags)

    fun changeType(item: MemoryItem, type: Int) {
        batchChangeType(listOf(item), type)
    }

    /**
     * 模块 2：多选地址批量更改数据类型（全异步 Dispatchers.IO）：
     * 1. 按新类型位宽通过 `engine.batchReadRawValues` 批量重读实时值；
     * 2. 重新计算指针分类；
     * 3. 对已冻结条目按新类型与位宽重新注册底层 `ValueLocker` 锁值；
     * 4. 原子更新搜索列表、保存列表、内存页并持久化保存列表。
     */
    fun batchChangeType(targets: List<MemoryItem>, newType: Int) {
        val cleanType = newType and 0x7F
        if (targets.isEmpty() || cleanType == 0 || cleanType == ValueType.AUTO) {
            toast("没有可修改的项目")
            return
        }
        val newSize = ValueType.sizeOf(cleanType).coerceIn(1, 8)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updatedByOldKey = withContext(Dispatchers.IO) {
                val readPairs = targets.map { it.address to newSize }.distinctBy { it.first }
                val liveVals = engine.batchReadRawValues(readPairs)
                val out = HashMap<Long, MemoryItem>(targets.size * 4 / 3 + 8)
                for (item in targets) {
                    val freshVal = liveVals[item.address] ?: engine.read(item.address, newSize)
                    val ptrKind = engine.classifyPointer(freshVal)
                    val updated = item.copy(
                        value = freshVal,
                        previousValue = freshVal,
                        flags = cleanType,
                        pointer = ptrKind
                    )
                    if (updated.freeze) {
                        engine.setFreeze(
                            address = updated.address,
                            flags = cleanType,
                            value = freshVal,
                            enabled = true,
                            freezeType = updated.freezeType,
                            freezeFrom = updated.freezeFrom,
                            freezeTo = updated.freezeTo
                        )
                    }
                    out[SelectionState.itemKey(item)] = updated
                }
                out
            }

            val updatedByAddr = HashMap<Long, MemoryItem>(updatedByOldKey.size * 2)
            updatedByOldKey.values.forEach { updatedByAddr[it.address] = it }

            var nextSavedSnapshot: List<MemoryItem> = emptyList()
            _ui.update { s ->
                fun remap(list: List<MemoryItem>): List<MemoryItem> = list.map { cur ->
                    updatedByOldKey[SelectionState.itemKey(cur)]
                        ?: updatedByAddr[cur.address]?.takeIf { targets.any { t -> sameItemKey(t, cur) } }
                        ?: cur
                }
                val nextSaved = remap(s.saved)
                nextSavedSnapshot = nextSaved
                syncFrozenSnapshot(nextSaved)
                val nextRaw = remap(s.rawResults)
                val nextRes = remap(s.results)
                val nextMem = s.memoryRows.map { row ->
                    val upd = updatedByAddr[row.address]
                    if (upd != null) row.copy(flags = cleanType, value = upd.value, pointer = upd.pointer) else row
                }
                s.copy(
                    isBatchBusy = false,
                    rawResults = nextRaw,
                    results = nextRes,
                    saved = nextSaved,
                    memoryRows = nextMem,
                    searchSelection = SelectionState.EMPTY,
                    savedSelection = SelectionState.EMPTY,
                    memorySelection = SelectionState.EMPTY,
                    sheetStack = emptyList()
                )
            }
            persistSavedListAsync(nextSavedSnapshot)
            toast("已批量更改 ${targets.size} 项的类型为 ${ValueType.longName(cleanType)}")
        }
    }

    /** 异步更改单个条目的地址与类型 */
    fun changeAddress(item: MemoryItem, newAddress: Long, newType: Int = item.flags) {
        val cleanType = if (newType == 0) item.flags else newType
        val size = ValueType.sizeOf(cleanType)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val updated = withContext(Dispatchers.IO) {
                if (item.freeze && item.address != newAddress) {
                    engine.setFreeze(item.address, item.flags, item.value, false)
                }
                val liveVal = engine.read(newAddress, size)
                val regCode = engine.regionCodeAt(newAddress)
                val next = item.copy(
                    address = newAddress,
                    value = liveVal,
                    previousValue = liveVal,
                    flags = cleanType,
                    region = regCode,
                    pointer = engine.classifyPointer(liveVal)
                )
                if (next.freeze) {
                    engine.setFreeze(newAddress, cleanType, liveVal, true, next.freezeType, next.freezeFrom, next.freezeTo)
                }
                next
            }
            var nextSavedSnapshot: List<MemoryItem> = emptyList()
            _ui.update { s ->
                val nextSaved = s.saved.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) }
                nextSavedSnapshot = nextSaved
                syncFrozenSnapshot(nextSaved)
                s.copy(
                    isBatchBusy = false,
                    rawResults = s.rawResults.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) },
                    results = s.results.map { if (sameItemKey(it, item)) updated else it }.distinctBy { SelectionState.itemKey(it) },
                    saved = nextSaved,
                    memoryRows = s.memoryRows.map { if (it.address == item.address) updated else it }.distinctBy { it.address },
                    sheetStack = emptyList()
                )
            }
            persistSavedListAsync(nextSavedSnapshot)
            toast("已更新地址为 ${updated.cachedAddressHex}")
        }
    }

    // ============ 模块 2：分离只读载入与只写保存列表文件（全异步 IO） ============

    /** 只读载入保存列表文件（绝不覆盖目标文件！） */
    fun loadListFromFile(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入有效的文件路径")
            return
        }
        setSetting("file_manager_path", clean)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(clean)
                    if (!file.exists()) {
                        return@runCatching Result.failure<Pair<String, List<MemoryItem>>>(IllegalStateException("文件不存在"))
                    }
                    val content = file.readText()
                    val loaded = SavedListFileCodec.parseFromText(content)
                    if (loaded.isEmpty()) {
                        return@runCatching Result.failure<Pair<String, List<MemoryItem>>>(IllegalStateException("文件中没有可载入的有效条目"))
                    }
                    loaded.forEach { item ->
                        if (item.freeze) {
                            engine.setFreeze(item.address, item.flags, item.value, true, item.freezeType, item.freezeFrom, item.freezeTo)
                        }
                    }
                    Result.success(file.name to loaded)
                }.getOrElse { Result.failure(it) }
            }
            _ui.update { it.copy(isBatchBusy = false) }
            result.onSuccess { (fileName, loaded) ->
                syncFrozenSnapshot(loaded)
                persistSavedListAsync(loaded)
                _ui.update {
                    it.copy(
                        saved = loaded,
                        savedSelection = SelectionState.EMPTY,
                        fileManagerPath = clean,
                        sheetStack = emptyList(),
                        tab = NextTab.SAVED_LIST
                    )
                }
                toast("已从 $fileName 载入 ${loaded.size} 项")
            }.onFailure { err ->
                val msg = err.message ?: "未知错误"
                if (msg == "文件不存在" || msg == "文件中没有可载入的有效条目") {
                    toast(msg)
                } else {
                    toast("文件读写失败: $msg")
                }
            }
        }
    }

    /** 只写保存列表到文件（`onlyChecked = true` 用于导出选中项） */
    fun saveListToFile(path: String, onlyChecked: Boolean = false) {
        val clean = path.trim()
        if (clean.isEmpty()) {
            toast("请输入有效的文件路径")
            return
        }
        val itemsToWrite = if (onlyChecked) {
            checkedItems().ifEmpty { _ui.value.saved }
        } else {
            _ui.value.saved
        }
        if (itemsToWrite.isEmpty()) {
            toast("保存列表为空，无可保存项目")
            return
        }
        setSetting("file_manager_path", clean)
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(clean)
                    file.parentFile?.mkdirs()
                    file.writeText(SavedListFileCodec.formatToText(itemsToWrite))
                    file.name
                }
            }
            _ui.update { it.copy(isBatchBusy = false) }
            result.onSuccess { fileName ->
                _ui.update { it.copy(fileManagerPath = clean, sheetStack = emptyList()) }
                toast("已保存 ${itemsToWrite.size} 项到 $fileName")
            }.onFailure { err ->
                toast("文件读写失败: ${err.message}")
            }
        }
    }

    fun setValueMask(mask: Int, forSaved: Boolean) {
        repo?.let { r -> scope.launch(Dispatchers.IO) { if (forSaved) r.setSavedFormatMask(mask) else r.setValueFormatMask(mask) } }
        _ui.update { if (forSaved) it.copy(valueMaskSaved = mask) else it.copy(valueMask = mask) }
        if (!forSaved) {
            refreshMemoryDisasmAsync()
        }
    }

    // ============ 内存浏览（4KB 单页 + 跳转高亮 + 持久化历史 + 反汇编） ============

    fun goto(address: Long, recordHistory: Boolean = true, highlightTarget: Boolean = true) {
        scope.launch {
            val prevBase = _ui.value.memoryBase
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            val pageBase = if (highlightTarget && targetAligned >= 12L) {
                (targetAligned - 12L) and -4L
            } else {
                targetAligned
            }
            val (rows, disasmMap) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
            val targetHex = java.lang.Long.toHexString(address).uppercase()
            var updatedGotoHist: List<String>? = null
            val limit = currentHistoryLimit()
            _ui.update {
                val nextBack = if (recordHistory && prevBase != pageBase && prevBase != 0L) {
                    (it.memoryHistoryBack + prevBase).takeLast(32)
                } else it.memoryHistoryBack
                val nextGotoHist = if (highlightTarget && targetHex.isNotEmpty() && limit > 0) {
                    (listOf(targetHex) + it.gotoHistory.filter { h -> h != targetHex }).take(limit.coerceAtMost(200)).also { list ->
                        updatedGotoHist = list
                    }
                } else it.gotoHistory
                it.copy(
                    memoryRows = rows,
                    memoryDisasm = disasmMap,
                    memoryBase = pageBase,
                    gotoHistory = nextGotoHist,
                    memoryHighlightAddress = if (highlightTarget) targetAligned else it.memoryHighlightAddress,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = if (recordHistory) emptyList() else it.memoryHistoryForward,
                    memorySelection = SelectionState.EMPTY,
                    busy = false,
                    tab = NextTab.MEMORY,
                    sheetStack = emptyList()
                )
            }
            updatedGotoHist?.let { persistGotoHistoryAsync(it) }
        }
    }

    private fun readMemoryPageWithDisasm(pageBase: Long): Pair<List<MemoryItem>, Map<Long, String>> {
        val count = MEMORY_PAGE_ROWS
        val block = engine.readBlock(pageBase, MEMORY_PAGE_BYTES)
        val rows = buildMemoryPageRows(pageBase, block, count)
        val asmArch = Formatter.activeAsmArch(_ui.value.valueMask)
        val disasm = if (asmArch != null && block.isNotEmpty()) {
            AsmEngineManager.disassembleBlockMap(asmArch, pageBase, block, count)
        } else {
            emptyMap()
        }
        return rows to disasm
    }

    private fun buildMemoryPageRows(pageBase: Long, block: ByteArray, count: Int): List<MemoryItem> {
        val candidatePtrs = LongArray(count) { r ->
            val off = (r and -2) * 4
            if (engine.x64 && off + 8 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24) or
                    ((block[off + 4].toLong() and 0xFF) shl 32) or
                    ((block[off + 5].toLong() and 0xFF) shl 40) or
                    ((block[off + 6].toLong() and 0xFF) shl 48) or
                    ((block[off + 7].toLong() and 0xFF) shl 56)
            } else if (off + 4 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24)
            } else 0L
        }
        val classifiedPtrs = engine.classifyPointers(candidatePtrs)
        val out = ArrayList<MemoryItem>(count)
        for (r in 0 until count) {
            val off = r * 4
            val rowAddr = pageBase + r * 4L
            val v = if (off + 4 <= block.size) {
                (block[off].toLong() and 0xFF) or
                    ((block[off + 1].toLong() and 0xFF) shl 8) or
                    ((block[off + 2].toLong() and 0xFF) shl 16) or
                    ((block[off + 3].toLong() and 0xFF) shl 24)
            } else 0L
            out.add(
                MemoryItem(
                    address = rowAddr,
                    value = v,
                    flags = ValueType.DWORD,
                    region = engine.regionCodeAt(rowAddr),
                    pointer = classifiedPtrs[r]
                )
            )
        }
        return out
    }

    fun focusHighlightedMemoryAddress() {
        val st = _ui.value
        val target = st.memoryHighlightAddress ?: st.memoryBase
        goto(target, recordHistory = false, highlightTarget = true)
    }

    fun gotoBoundary(address: Long, isUpward: Boolean) {
        scope.launch {
            val prevBase = _ui.value.memoryBase
            _ui.update { it.copy(busy = true, busyText = "读取内存页") }
            val targetAligned = address and -4L
            val pageBase = if (isUpward) {
                val span = (MEMORY_PAGE_BYTES - 4).toLong()
                if (java.lang.Long.compareUnsigned(targetAligned, span) >= 0) {
                    (targetAligned - span) and -4L
                } else 0L
            } else {
                targetAligned
            }
            val (rows, disasmMap) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
            _ui.update {
                val nextBack = if (prevBase != pageBase && prevBase != 0L) {
                    (it.memoryHistoryBack + prevBase).takeLast(32)
                } else it.memoryHistoryBack
                it.copy(
                    memoryRows = rows,
                    memoryDisasm = disasmMap,
                    memoryBase = pageBase,
                    memoryHighlightAddress = targetAligned,
                    memoryScrollTrigger = it.memoryScrollTrigger + 1,
                    memoryHistoryBack = nextBack,
                    memoryHistoryForward = emptyList(),
                    memorySelection = SelectionState.EMPTY,
                    busy = false,
                    tab = NextTab.MEMORY
                )
            }
        }
    }

    fun memoryScrollBy(rows: Int) {
        val base = (_ui.value.memoryBase + rows * 4L).coerceAtLeast(0L)
        goto(base, recordHistory = false, highlightTarget = false)
    }

    fun memoryBack() {
        val st = _ui.value
        val prev = st.memoryHistoryBack.lastOrNull()
        if (prev == null) {
            memoryScrollBy(-MEMORY_PAGE_ROWS)
            return
        }
        _ui.update {
            it.copy(
                memoryHistoryBack = it.memoryHistoryBack.dropLast(1),
                memoryHistoryForward = it.memoryHistoryForward + it.memoryBase
            )
        }
        goto(prev, recordHistory = false, highlightTarget = true)
    }

    fun memoryForward() {
        val st = _ui.value
        val next = st.memoryHistoryForward.lastOrNull()
        if (next == null) {
            memoryScrollBy(MEMORY_PAGE_ROWS)
            return
        }
        _ui.update {
            it.copy(
                memoryHistoryForward = it.memoryHistoryForward.dropLast(1),
                memoryHistoryBack = it.memoryHistoryBack + it.memoryBase
            )
        }
        goto(next, recordHistory = false, highlightTarget = true)
    }

    // ============ 内存导出 & 诚实的内存页分配 ============

    fun dumpMemory(startAddr: Long, endAddr: Long, dirPath: String) {
        if (endAddr <= startAddr) {
            toast("结束地址必须大于起始地址")
            return
        }
        val cleanDir = dirPath.trim().trimEnd('/').ifEmpty { AppPaths.DUMP_DIR }
        setSetting("dump_dir_path", cleanDir)
        val fileName = "${java.lang.Long.toHexString(startAddr).uppercase()}-${java.lang.Long.toHexString(endAddr).uppercase()}.bin"
        val fullPath = "$cleanDir/$fileName"
        scope.launch {
            _ui.update { it.copy(busy = true, busyText = "导出内存中") }
            val ok = withContext(Dispatchers.IO) {
                engine.dumpMemory(startAddr, endAddr, fullPath)
            }
            _ui.update { it.copy(busy = false, dumpDirPath = cleanDir, sheetStack = emptyList()) }
            if (ok) {
                toast("已导出内存区间到 $fullPath")
            } else {
                toast("导出内存失败")
            }
        }
    }

    /** 目标进程真实匿名内存页分配 */
    fun allocateMemoryPage(address: Long, read: Boolean, write: Boolean, exec: Boolean) {
        scope.launch {
            _ui.update { it.copy(busy = true, busyText = "正在分配内存页") }
            val (allocAddr, msg, refreshedRegions) = withContext(Dispatchers.IO) {
                val (a, m) = engine.allocatePage(address, read, write, exec)
                val regs = if (a != 0L) aggregate(engine.regions()) else null
                Triple(a, m, regs)
            }
            _ui.update { st ->
                st.copy(
                    busy = false,
                    regions = refreshedRegions ?: st.regions
                )
            }
            toast(msg)
            if (allocAddr != 0L) {
                closeAllSheets()
                goto(allocAddr, recordHistory = true, highlightTarget = true)
            }
        }
    }

    // ============ 历史（完整持久化） ============

    fun clearHistory() {
        historyStore.clear()
        persistSearchHistoryAsync()
        persistGotoHistoryAsync(emptyList())
        _ui.update { it.copy(history = emptyList(), gotoHistory = emptyList()) }
        toast("已清空历史")
    }

    fun removeHistory(v: String) {
        historyStore.remove(v)
        persistSearchHistoryAsync()
        _ui.update { it.copy(history = historyStore.list()) }
    }

    fun toggleHistoryPin(v: String) {
        historyStore.togglePin(v)
        persistSearchHistoryAsync()
        _ui.update { it.copy(history = historyStore.list()) }
    }

    fun isHistoryPinned(v: String): Boolean = historyStore.isPinned(v)

    fun pickHistory(v: String) = _ui.update { st ->
        val parentSheet = st.sheetStack.dropLast(1).lastOrNull() ?: Sheet.SEARCH
        val nextStack = if (st.sheetStack.size > 1) st.sheetStack.dropLast(1) else listOf(parentSheet)
        when (parentSheet) {
            Sheet.EDITOR -> st.copy(lastBatchEditValue = v, sheetStack = nextStack)
            else -> st.copy(search = st.search.copy(text = v), sheetStack = nextStack)
        }
    }

    // ============ 悬浮窗 ============

    fun toggleOverlay(app: Application, enabled: Boolean) {
        if (enabled) {
            setFloatingBallVisible(true)
            com.littlecodesauce.ggnext.service.OverlayManager.start(app)
            toast("已启动悬浮球，短按打开面板")
        } else {
            setFloatingBallVisible(false)
            setPanelVisible(false)
            com.littlecodesauce.ggnext.service.OverlayManager.stop(app)
            toast("已关闭悬浮窗")
        }
    }

    // ============ 工具栏 ============

    fun toolList(tab: NextTab): List<ToolId> = _ui.value.tools[tab] ?: when (tab) {
        NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
        NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
        NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
        NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
        NextTab.SETTINGS, NextTab.AI -> emptyList()
    }

    fun moveTool(tab: NextTab, from: Int, to: Int) {
        val list = toolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun toggleTool(tab: NextTab, id: ToolId) {
        val list = toolList(tab).toMutableList()
        if (list.contains(id)) {
            if (list.size > 1) list.remove(id)
        } else {
            list.add(id)
        }
        _ui.update { it.copy(tools = it.tools + (tab to list)) }
        persistToolsForTab(tab, list)
    }

    fun resetTools(tab: NextTab) {
        val def = when (tab) {
            NextTab.SEARCH_LIST -> DEFAULT_TOOLS_SEARCH
            NextTab.SAVED_LIST -> DEFAULT_TOOLS_SAVED
            NextTab.MEMORY -> DEFAULT_TOOLS_MEMORY
        NextTab.DEBUG -> DEFAULT_TOOLS_DEBUG
        NextTab.SETTINGS, NextTab.AI -> emptyList()
    }
    _ui.update {
            it.copy(
                tools = it.tools + (tab to def),
                pinnedTools = it.pinnedTools + (tab to DEFAULT_PINNED_TOOLS)
            )
        }
        persistToolsForTab(tab, def)
        persistPinnedToolsForTab(tab, DEFAULT_PINNED_TOOLS)
    }

    fun pinnedToolList(tab: NextTab): List<ToolId> = _ui.value.pinnedTools[tab] ?: DEFAULT_PINNED_TOOLS

    fun pinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (!list.contains(id)) {
            list.add(id)
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${AppStrings.tr(id.label)}」固定到右侧工具栏")
        }
    }

    fun unpinTool(tab: NextTab, id: ToolId) {
        val list = pinnedToolList(tab).toMutableList()
        if (list.size <= 1) {
            toast("固定工具栏至少需保留一项")
            return
        }
        if (list.remove(id)) {
            _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
            persistPinnedToolsForTab(tab, list)
            toast("已将「${AppStrings.tr(id.label)}」移回流动工具栏")
        }
    }

    fun movePinnedTool(tab: NextTab, from: Int, to: Int) {
        val list = pinnedToolList(tab).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.update { it.copy(pinnedTools = it.pinnedTools + (tab to list)) }
        persistPinnedToolsForTab(tab, list)
    }

    /** 模块 1：终止目标进程全异步 IO，绝不阻塞主线程 */
    fun terminateTargetProcess() {
        val target = _ui.value.target ?: run {
            toast("未选择目标进程")
            return
        }
        val pid = target.pid
        scope.launch {
            withContext(Dispatchers.IO) {
                engine.detach()
                if (Shell.hasRoot()) {
                    Shell.runAs("kill -9 $pid", asRoot = true)
                } else {
                    try {
                        android.os.Process.killProcess(pid)
                    } catch (_: Throwable) {
                        Shell.runAs("kill -9 $pid", asRoot = false)
                    }
                }
            }
            _ui.update {
                it.copy(
                    target = null,
                    attachedOk = false,
                    processPaused = false,
                    rawResults = emptyList(),
                    results = emptyList(),
                    memoryRows = emptyList(),
                    searchSelection = SelectionState.EMPTY,
                    memorySelection = SelectionState.EMPTY
                )
            }
            toast("已终止目标进程 [${pid}] ${target.label}")
        }
    }

    fun exitApp() {
        appContext?.let { ctx ->
            val intent = android.content.Intent(ctx, com.littlecodesauce.ggnext.service.FloatingIconService::class.java)
            ctx.stopService(intent)
        }
        _ui.update { it.copy(floatingBallVisible = false, panelVisible = false, sheetStack = emptyList()) }
        toast("修改器已退出")
    }

    fun copyMemory(fromAddr: Long, toAddr: Long, bytes: Long) {
        if (bytes <= 0L) {
            toast("字节数必须大于 0")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = engine.copyMemory(fromAddr, toAddr, bytes)
            withContext(Dispatchers.Main) {
                if (ok) {
                    toast("已成功复制 $bytes 字节到 0x${java.lang.Long.toHexString(toAddr).uppercase()}")
                    goto(_ui.value.memoryBase, recordHistory = false, highlightTarget = false)
                    closeAllSheets()
                } else {
                    toast("复制内存失败，请检查地址范围是否可读写")
                }
            }
        }
    }

    // ============ Keystone / Capstone 独立汇编引擎扩展包管理 ============

    fun importAsmPackFromUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val status = withContext(Dispatchers.IO) {
                AsmEngineManager.importFromUri(ctx, uri)
            }
            _ui.update { it.copy(isBatchBusy = false, asmPack = status) }
            if (status.fullyReady) {
                refreshMemoryDisasmAsync()
                toast("汇编引擎包已导入并加载 (${status.abi})")
            } else if (status.keystoneReady || status.capstoneReady) {
                refreshMemoryDisasmAsync()
                toast("已部分加载汇编引擎：${status.error.ifBlank { status.abi }}")
            } else {
                toast(status.error.ifBlank { "导入汇编引擎包失败" })
            }
        }
    }

    fun importAsmPackFromFile(path: String) {
        val ctx = appContext ?: return
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val status = withContext(Dispatchers.IO) {
                AsmEngineManager.importFromFile(ctx, File(path))
            }
            _ui.update { it.copy(isBatchBusy = false, asmPack = status) }
            if (status.fullyReady) {
                refreshMemoryDisasmAsync()
                toast("汇编引擎包已导入并加载 (${status.abi})")
            } else if (status.keystoneReady || status.capstoneReady) {
                refreshMemoryDisasmAsync()
                toast("已部分加载汇编引擎：${status.error.ifBlank { status.abi }}")
            } else {
                toast(status.error.ifBlank { "导入汇编引擎包失败" })
            }
        }
    }

    fun clearAsmPack() {
        val ctx = appContext ?: return
        scope.launch {
            val status = withContext(Dispatchers.IO) { AsmEngineManager.clearPack(ctx) }
            _ui.update { it.copy(asmPack = status, memoryDisasm = emptyMap()) }
            toast("已移除外置汇编引擎包")
        }
    }

    fun refreshMemoryDisasmAsync() {
        val st = _ui.value
        val arch = Formatter.activeAsmArch(st.valueMask)
        if (arch == null || st.memoryRows.isEmpty() || !st.attachedOk) {
            if (st.memoryDisasm.isNotEmpty()) {
                _ui.update { it.copy(memoryDisasm = emptyMap()) }
            }
            return
        }
        val base = st.memoryBase
        scope.launch(Dispatchers.IO) {
            val block = engine.readBlock(base, MEMORY_PAGE_BYTES)
            val disasm = AsmEngineManager.disassembleBlockMap(arch, base, block, MEMORY_PAGE_ROWS)
            _ui.update { cur ->
                if (cur.memoryBase == base) cur.copy(memoryDisasm = disasm) else cur
            }
        }
    }

    fun writeAssembly(
        address: Long,
        arch: AsmArch,
        asmCode: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val clean = asmCode.trim()
        if (clean.isEmpty()) {
            onDone(false, "请输入汇编指令")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val result = withContext(Dispatchers.IO) {
                val res = AsmEngineManager.assemble(arch, address, clean)
                if (res.isFailure) {
                    return@withContext false to (res.exceptionOrNull()?.message ?: "汇编失败")
                }
                val bytes = res.getOrThrow()
                if (bytes.isEmpty()) {
                    return@withContext false to "未生成有效机器码"
                }
                val ok = engine.writeBlock(address, bytes)
                if (!ok) {
                    return@withContext false to "写入目标地址内存失败（请确认权限或内存页可写）"
                }
                true to "已写入 ${bytes.size} 字节 (${AsmEngineManager.bytesToSpacedHex(bytes.take(12).toByteArray())}${if (bytes.size > 12) " …" else ""})"
            }
            _ui.update { it.copy(isBatchBusy = false) }
            if (result.first) {
                val pageBase = _ui.value.memoryBase
                if (_ui.value.memoryRows.isNotEmpty()) {
                    val (rows, disasm) = withContext(Dispatchers.IO) { readMemoryPageWithDisasm(pageBase) }
                    _ui.update { it.copy(memoryRows = rows, memoryDisasm = disasm) }
                }
                toast(result.second)
            }
            onDone(result.first, result.second)
        }
    }

    // ============ 硬件断点调试器 (Perf / Ptrace) ============

    fun breakpointBackendMode(st: Ui = _ui.value): BpMode {
        return when (st.settings["bp_backend"]) {
            "perf" -> BpMode.PERF
            "ptrace" -> BpMode.PTRACE
            else -> if (st.workMode == WorkMode.ROOT && st.hasRootPermission) BpMode.PERF else BpMode.PTRACE
        }
    }

    fun setBreakpointBackendMode(mode: BpMode) {
        val raw = if (mode == BpMode.PERF) "perf" else "ptrace"
        setSetting("bp_backend", raw)
        toast("已切换断点后端为 ${mode.label}")
    }

    fun modifyBreakpointRegister(
        bpId: Int,
        tid: Int,
        hitPc: Long,
        regName: String,
        hexValue: String,
        isFp: Boolean,
        onDone: (Boolean, String) -> Unit
    ) {
        scope.launch {
            val (ok, err, snap) = withContext(Dispatchers.IO) {
                val (wOk, wErr) = engine.bpSetRegister(bpId, tid, hitPc, regName, hexValue, isFp)
                val nextSnap = if (wOk) engine.bpSnapshot() else null
                Triple(wOk, wErr, nextSnap)
            }
            if (ok && snap != null) {
                _ui.update { it.copy(breakpoints = snap) }
                toast("已修改寄存器 $regName = ${hexValue.uppercase()}")
                onDone(true, "")
            } else {
                val msg = err.ifBlank { "寄存器写回失败" }
                toast(msg)
                onDone(false, msg)
            }
        }
    }

    fun defaultBreakpointAddressHex(): String {
        val st = _ui.value
        val addr = st.contextItem?.address
            ?: checkedItems().firstOrNull()?.address
            ?: st.memoryHighlightAddress
            ?: st.memoryBase
        return if (addr != 0L) java.lang.Long.toUnsignedString(addr, 16).uppercase() else ""
    }

    fun addBreakpoint(
        address: Long,
        type: BpType,
        sizeBytes: Int,
        mode: BpMode,
        captureCallstack: Boolean
    ) {
        if (!_ui.value.attachedOk) {
            toast("请先附加目标进程")
            return
        }
        scope.launch {
            _ui.update { it.copy(isBatchBusy = true) }
            val (bpId, err) = withContext(Dispatchers.IO) {
                engine.bpAdd(address, type, sizeBytes, mode, captureCallstack)
            }
            if (bpId > 0) {
                val snap = withContext(Dispatchers.IO) { engine.bpSnapshot() }
                _ui.update { st ->
                    st.copy(
                        isBatchBusy = false,
                        breakpoints = snap,
                        tab = NextTab.DEBUG,
                        sheetStack = emptyList()
                    )
                }
                ensureBpPolling()
                toast("已添加断点 #$bpId (${java.lang.Long.toUnsignedString(address, 16).uppercase()})")
            } else {
                _ui.update { it.copy(isBatchBusy = false) }
                toast(err.ifBlank { "添加断点失败" })
            }
        }
    }

    fun removeBreakpoint(bpId: Int) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpRemove(bpId)
                engine.bpSnapshot()
            }
            _ui.update { st ->
                st.copy(
                    breakpoints = snap,
                    selectedBpIds = st.selectedBpIds - bpId
                )
            }
            if (snap.none { it.running }) {
                bpPollingJob?.cancel()
                bpPollingJob = null
            }
            toast("已移除断点 #$bpId")
        }
    }

    fun removeSelectedBreakpoints() {
        val ids = _ui.value.selectedBpIds.ifEmpty { _ui.value.breakpoints.map { it.id }.toSet() }
        if (ids.isEmpty()) {
            toast("当前没有可删除的断点")
            return
        }
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                ids.forEach { engine.bpRemove(it) }
                engine.bpSnapshot()
            }
            _ui.update { st ->
                st.copy(
                    breakpoints = snap,
                    selectedBpIds = emptySet()
                )
            }
            if (snap.none { it.running }) {
                bpPollingJob?.cancel()
                bpPollingJob = null
            }
            toast("已删除 ${ids.size} 个断点")
        }
    }

    fun toggleBreakpointPause(bpId: Int) {
        val bp = _ui.value.breakpoints.firstOrNull { it.id == bpId } ?: return
        val nextPaused = !bp.paused
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpSetPaused(bpId, nextPaused)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
        }
    }

    fun toggleAllBreakpointsPause() {
        val list = _ui.value.breakpoints
        if (list.isEmpty()) return
        val anyRunning = list.any { it.isActive }
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpSetAllPaused(anyRunning)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
            toast(if (anyRunning) "已暂停全部断点" else "已恢复全部断点")
        }
    }

    fun clearBreakpointHits(bpId: Int = 0) {
        scope.launch {
            val snap = withContext(Dispatchers.IO) {
                engine.bpClearHits(bpId)
                engine.bpSnapshot()
            }
            _ui.update { it.copy(breakpoints = snap) }
            toast("已清空断点命中记录")
        }
    }

    fun toggleBpSelection(bpId: Int) {
        _ui.update { st ->
            val next = if (bpId in st.selectedBpIds) st.selectedBpIds - bpId else st.selectedBpIds + bpId
            st.copy(selectedBpIds = next)
        }
    }

    fun selectAllBreakpoints() {
        _ui.update { st -> st.copy(selectedBpIds = st.breakpoints.map { it.id }.toSet()) }
    }

    fun invertBpSelection() {
        _ui.update { st ->
            val all = st.breakpoints.map { it.id }.toSet()
            st.copy(selectedBpIds = all - st.selectedBpIds)
        }
    }

    fun clearBpSelection() {
        _ui.update { it.copy(selectedBpIds = emptySet()) }
    }

    fun refreshBreakpointsNow() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) { engine.bpSnapshot() }
            _ui.update { it.copy(breakpoints = snap) }
            if (snap.any { it.running }) ensureBpPolling()
        }
    }

    private fun ensureBpPolling() {
        if (bpPollingJob?.isActive == true) return
        bpPollingJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(420L)
                val st = _ui.value
                if (!st.attachedOk) {
                    _ui.update { it.copy(breakpoints = emptyList(), selectedBpIds = emptySet()) }
                    break
                }
                val snap = engine.bpSnapshot()
                _ui.update { it.copy(breakpoints = snap) }
                if (snap.isEmpty() || snap.none { it.running }) {
                    break
                }
            }
        }
    }

    // ============ MCP 服务桥接（复用真实引擎实例与状态同步） ============

    internal fun engineForMcp(): MemoryEngine = engine

    internal fun attachFromMcpSync(proc: TargetProcess): Boolean {
        val st = _ui.value
        AppLog.i("MCP", "attach_process 开始 pid=${proc.pid} label=${proc.label} pkg=${proc.packageName} x64=${proc.x64}")
        // v0.9.1 崩溃修复：切换目标前必须先停掉旧进程上的断点/扫描/冻结线程。
        // 否则 perf/ptrace 断点线程仍指向旧 PID，attach 切换 native backend 时会访问失效句柄导致 SIGSEGV。
        try {
            runCatching { engine.cancelScan() }
            runCatching { engine.bpStopAll() }
            runCatching { engine.clearNativeFreezes() }
            bpPollingJob?.cancel()
            bpPollingJob = null
            hasFuzzySession = false

            val ok = engine.attach(
                targetPid = proc.pid,
                targetX64 = proc.x64,
                isRootMode = (st.workMode == WorkMode.ROOT),
                context = appContext
            )
            if (ok) {
                applyStealthFromSettings()
                val freshRegions = engine.regions()
                _ui.update { cur ->
                    cur.copy(
                        target = proc.copy(x64 = engine.x64),
                        attachedOk = true,
                        processPaused = false,
                        results = emptyList(),
                        rawResults = emptyList(),
                        totalCount = 0L,
                        regions = aggregate(freshRegions),
                        selectedRegions = resolveRegionsForPackage(cur.settings, proc.packageName),
                        searchSelection = SelectionState.EMPTY,
                        memorySelection = SelectionState.EMPTY,
                        memoryRows = emptyList(),
                        breakpoints = emptyList(),
                        selectedBpIds = emptySet(),
                        fuzzy = cur.fuzzy.copy(active = false)
                    )
                }
                AppLog.i("MCP", "attach_process 成功 pid=${proc.pid} regions=${freshRegions.size}")
            } else {
                AppLog.w("MCP", "attach_process 失败 pid=${proc.pid} err=${engine.lastAttachError}")
            }
            return ok
        } catch (t: Throwable) {
            AppLog.e("MCP", "attach_process 异常 pid=${proc.pid}", t)
            return false
        }
    }

    internal fun syncSearchResultsFromMcp(items: List<MemoryItem>, totalCount: Long) {
        _ui.update { cur ->
            cur.copy(
                results = items,
                totalCount = totalCount
            )
        }
    }

    internal fun setSelectedRegionsFromMcp(regions: Set<String>) {
        if (regions.isEmpty()) return
        _ui.update { it.copy(selectedRegions = regions) }
        persistSelectedRegions(regions, _ui.value.target?.packageName)
    }

    internal fun removeSearchAddressesFromMcp(addresses: Set<Long>): Int {
        if (addresses.isEmpty()) return 0
        engine.markRemoved(addresses)
        var removedCount = 0
        _ui.update { cur ->
            val filtered = cur.results.filterNot { it.address in addresses }
            removedCount = cur.results.size - filtered.size
            val nextTotal = (cur.totalCount - removedCount.toLong()).coerceAtLeast(filtered.size.toLong())
            cur.copy(results = filtered, totalCount = nextTotal)
        }
        return removedCount
    }

    internal fun saveItemsFromMcp(items: List<MemoryItem>): Int {
        if (items.isEmpty()) return 0
        var added = 0
        _ui.update { cur ->
            val existingMap = cur.saved.associateBy { it.address to (it.flags and 0x7F) }.toMutableMap()
            for (item in items) {
                val key = item.address to (item.flags and 0x7F)
                if (!existingMap.containsKey(key)) added++
                existingMap[key] = item
            }
            cur.copy(saved = existingMap.values.toList())
        }
        persistSavedListAsync(_ui.value.saved)
        return added
    }

    internal fun removeSavedAddressesFromMcp(addresses: Set<Long>): Int {
        if (addresses.isEmpty()) return 0
        addresses.forEach { addr -> engine.setFreeze(addr, ValueType.DWORD, 0L, enabled = false) }
        var removed = 0
        _ui.update { cur ->
            val filtered = cur.saved.filterNot { it.address in addresses }
            removed = cur.saved.size - filtered.size
            cur.copy(saved = filtered)
        }
        persistSavedListAsync(_ui.value.saved)
        return removed
    }

    internal fun updateSavedNoteFromMcp(address: Long, note: String): Boolean {
        var found = false
        _ui.update { cur ->
            val next = cur.saved.map { item ->
                if (item.address == address) {
                    found = true
                    item.copy(name = note, note = note)
                } else item
            }
            cur.copy(saved = next)
        }
        if (found) persistSavedListAsync(_ui.value.saved)
        return found
    }

    internal fun clearSavedFromMcp(): Int {
        val count = _ui.value.saved.size
        engine.clearNativeFreezes()
        _ui.update { it.copy(saved = emptyList()) }
        persistSavedListAsync(emptyList())
        return count
    }

    internal fun controlProcessFromMcp(action: String): Pair<Boolean, String> {
        val st = _ui.value
        val pid = engine.pid
        if (!engine.attachedOk || pid <= 0) return false to "No target process attached"
        val asRoot = (st.workMode == WorkMode.ROOT && Shell.hasRoot())
        return when (action) {
            "pause" -> {
                engine.setFreezePaused(true)
                val res = Shell.runAs("kill -STOP $pid", asRoot = asRoot)
                if (res.exitCode == 0) {
                    _ui.update { it.copy(processPaused = true) }
                    true to "Process $pid paused"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to pause process $pid" }
            }
            "resume" -> {
                val res = Shell.runAs("kill -CONT $pid", asRoot = asRoot)
                engine.setFreezePaused(false)
                if (res.exitCode == 0) {
                    _ui.update { it.copy(processPaused = false) }
                    true to "Process $pid resumed"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to resume process $pid" }
            }
            "kill" -> {
                engine.clearNativeFreezes()
                engine.bpStopAll()
                val res = Shell.runAs("kill -9 $pid", asRoot = asRoot)
                if (res.exitCode == 0) {
                    engine.detach()
                    _ui.update { it.copy(attachedOk = false, processPaused = false, target = null, results = emptyList(), totalCount = 0L) }
                    true to "Process $pid terminated"
                } else false to res.out.joinToString(" ").ifBlank { "Failed to terminate process $pid" }
            }
            else -> false to "Unsupported action: $action"
        }
    }
}
