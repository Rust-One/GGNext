package com.littlecodesauce.ggnext.core

import java.util.concurrent.atomic.AtomicReference

/**
 * 纯 Kotlin 线程安全双语核心（0 = 简体中文，1 = English）：
 * - 可在 Composable、非 Composable（Session Toast、NumberParser 报错）及 JVM 单元测试中直接调用；
 * - Compose 层通过 `LocalAppLang` 与 `I18n.currentLang` 驱动全界面实时热切换，无需重启。
 */
object AppStrings {

    const val LANG_ZH = "0"
    const val LANG_EN = "1"

    private val currentLangRef = AtomicReference(LANG_ZH)

    @Volatile
    var onLangChanged: ((String) -> Unit)? = null

    val currentLang: String
        get() = currentLangRef.get()

    val isEnglish: Boolean
        get() = currentLang == LANG_EN || currentLang == "2"

    fun setLang(lang: String) {
        val normalized = if (lang == LANG_EN || lang == "2") LANG_EN else LANG_ZH
        val prev = currentLangRef.getAndSet(normalized)
        if (prev != normalized) {
            onLangChanged?.invoke(normalized)
        }
    }

    fun tr(text: String, lang: String = currentLang): String {
        if (text.isEmpty()) return text
        return when (lang) {
            LANG_EN, "2" -> toEnglish(text)
            else -> text
        }
    }

    fun helpText(lang: String = currentLang): String =
        if (lang == LANG_EN || lang == "2") HELP_TEXT_EN else HELP_TEXT_ZH

    fun changelogText(lang: String = currentLang): String =
        if (lang == LANG_EN || lang == "2") CHANGELOG_TEXT_EN else CHANGELOG_TEXT_ZH

    private const val HELP_TEXT_ZH = """• 精确与高级搜索：支持数字、范围 100~200、反向范围 100~~200、文本 'abc' / "abc"、HEX 数值（1234h / h4142）、联合搜索 1;2;3:512、有序联合 1;2::64、异或 100X7 / 100X4。
• 模糊搜索：先采样初始值，再按「有变化 / 无变化 / 增加了 / 减少了 / 差值 / 第 N 个结果」逐步缩小范围。
• 搜索中可随时点击进度条右侧「取消」按钮中断底层 C++20 扫描线程。
• 列表操作：点击地址行快捷编辑，长按行或点击工具栏按钮可批量修改、批量更改类型、锁定冻结（正常/递增/递减/范围钳制）、计算偏移、指针搜索与内存浏览。
• 悬浮窗：在主页启动悬浮球，短按悬浮球展开面板（展开时自动隐藏悬浮球）。"""

    private const val HELP_TEXT_EN = """• Exact & Advanced Search: Supports single values, ranges (100~200), inverted ranges (100~~200), text ('abc' / "abc"), HEX (1234h / h4142), group search (1;2;3:512), ordered group (1;2::64), and XOR (100X7 / 100X4).
• Fuzzy Search: Take an initial unknown snapshot, then narrow results by Changed / Unchanged / Increased / Decreased / Delta / Only N-th result.
• Tap 'Cancel' on the search progress bar anytime to abort the native C++20 scan immediately.
• List Operations: Tap an item to edit, or use toolbar/context actions for batch edit, batch type change, freeze modes (Normal / Increment / Decrement / Range clamp), offset calculator, pointer search, and memory browser.
• Floating Overlay: Start the floating ball from Home; tap it to open the panel (auto-hides while panel is open)."""

    private const val CHANGELOG_TEXT_ZH = """GGNext v0.6 更新日志：
1. 主线程零阻塞与引擎稳定性：Root 权限检测、批量读写、文件导入导出、进程终止及偏移/模块读取全面异步化；新增守护进程超时自愈与目标进程存活监测。
2. 端到端扫描取消与原生覆盖：支持扫描中一键中断 C++20 线程；原生下推地址区间/蒙版、不等于 (!=)、反向范围 (~~) 与模糊 onlyNth 步进过滤；统一指针偏移语义。
3. 批量修改与类型切换：新增多选地址批量更改类型、4 种冻结模式（正常/递增/递减/范围钳制）UI 配置、加密/XOR 写回自动重编码及只读/只写分离的列表文件管理。
4. 性能与架构升级：引入 O(1) 全选/反选/清空状态模型；拆分搜索进度与 Toast 独立状态流；实现分层弹窗返回栈与纯 JSON 历史/保存列表持久化。
5. 界面体验优化：完善中英文实时热切换、修复浅色模式对比度、消除横屏灰底遮罩、新增横屏左侧竖排导航栏并扩展至 16 套主题配色。"""

    private const val CHANGELOG_TEXT_EN = """GGNext v0.6 Changelog:
1. Main-Thread ANR Elimination & Stability: Moved Root checks, batch edits, file I/O, process kill, and offset/module previews off the main thread; added daemon watchdog recovery and target process liveness monitoring.
2. End-to-End Cancellation & Native Pushdown: Added instant native scan cancellation, address range/mask pushdown, native != and inverted range (~~), native fuzzy onlyNth stride, and unified pointer offset semantics.
3. Batch Edit & Type Change: Added batch data-type switching for multi-selected items, all 4 freeze modes (Normal / Increment / Decrement / Range clamp) in Edit dialog, encrypted/XOR re-encoding on writeback, and separate Load/Save list file actions.
4. Performance & Architecture: Introduced O(1) bulk selection across all tabs, isolated progress/toast StateFlows, layered sheet navigation stack, and pure-JSON persistence for search/goto/script history and saved list.
5. UI Polish: Full live Chinese/English switching, light-mode contrast fixes, zero landscape dim scrim, landscape left vertical navigation rail, and 16 curated theme presets."""

    private val enMap: Map<String, String> = mapOf(
        "主页" to "Home",
        "设置" to "Settings",
        "菜单" to "Menu",
        "软件设置" to "App Settings",
        "启动" to "Start",
        "已启动" to "Running",
        "未启动" to "Stopped",
        "启动悬浮窗" to "Start Overlay",
        "停止悬浮窗" to "Stop Overlay",
        "停止服务" to "Stop Service",
        "展开面板" to "Open Panel",
        "展开修改器" to "Open Modifier",
        "Android 内存调试工具" to "Android Memory Debugger",
        "悬浮窗运行中" to "Overlay Active",
        "悬浮窗未启动" to "Overlay Inactive",
        "短按悬浮球打开调试面板，长按悬浮球调节游戏速率" to "Tap the floating ball to open the panel, hold to adjust speed",
        "短按悬浮球打开调试面板" to "Tap the floating ball to open the debugging panel",
        "启动后将在屏幕显示可拖拽悬浮球，点击即可附加进程并检索内存" to "Shows a draggable floating ball on screen to attach processes and inspect memory",
        "运行环境" to "Environment",
        "运行方式" to "Operation Mode",
        "已允许在其他应用上层显示" to "Display over other apps allowed",
        "尚未授予系统悬浮窗权限" to "Overlay permission not granted yet",
        "已授权" to "Granted",
        "未授权" to "Unauthorized",
        "共享 UID" to "Shared UID",
        "Root" to "Root",
        "免 Root" to "No Root",
        "Root 模式" to "Root Mode",
        "免 Root 模式" to "No-Root Mode",
        "通过 Root 权限进行全系统跨进程读写（支持所有已运行应用与游戏）" to "System-wide cross-process memory I/O via Root access",
        "针对虚拟空间/双开沙箱（所有应用共享相同 UID），无需 Root 跨进程读写" to "For virtual spaces / shared-UID sandboxes without Root",
        "Root 权限已获取，支持全系统进程读写" to "Root access ready for system-wide process memory I/O",
        "未检测到 Root 授权，点击检测或切换至免 Root" to "Root access not detected, tap to recheck or switch to No-Root",
        "适用虚拟空间及共享 UID 环境，无需 Root 权限" to "For virtual spaces and shared UID environments without Root",
        "主题配色" to "Theme Color",
        "界面语言" to "Language",
        "语言" to "Language",
        "恢复默认设置" to "Reset Default Settings",
        "重置主题、透明度、屏幕占比、字号与语言设置" to "Reset theme, opacity, scale, font size and language",
        "冻结间隔" to "Freeze Interval",
        "使用声明与免责协议" to "Terms of Use & Disclaimer",
        "请完整阅读以下条款后决定是否继续使用" to "Please read the terms carefully before continuing",
        "一、工具性质与适用范围" to "1. Purpose and Intended Scope",
        "GGNext 为开源本地内存分析与调试工具，仅适用于程序开发测试、故障排查、安全研究及经明确授权的本地环境实验。任何功能均按通用内存读写机制提供，不针对任何特定商业软件、游戏或网络服务。" to "GGNext is an open-source local memory analysis and debugging tool intended solely for development testing, troubleshooting, security research, and authorized local environments. Its features provide general memory read/write operations and do not target any specific commercial software, game, or online service.",
        "二、严禁非法及侵权用途" to "2. Prohibited Uses",
        "严禁将本软件用于制作或分发游戏外挂、篡改联机数据、破坏计算机信息系统、绕过付费与版权保护、窃取敏感信息或任何违反当地法律法规及第三方服务协议的行为。如您所在地区法律限制此类工具的使用，请立即停止使用并卸载。" to "You must not use this software to create or distribute cheats, alter online data, compromise computer systems, bypass billing or copyright protections, extract sensitive data, or violate applicable laws or third-party terms of service. Discontinue use and uninstall immediately if restricted in your jurisdiction.",
        "三、使用者独立责任" to "3. Sole User Responsibility",
        "因使用本软件而产生的一切技术后果、数据损坏、账号封禁、设备异常及法律责任，均由使用者本人独立承担。软件作者及代码贡献者无法控制任何第三方的具体操作方式，亦不参与、不教唆任何违规用途。" to "You assume full responsibility for all technical outcomes, data corruption, account penalties, device issues, and legal liabilities arising from your use of this software. The authors and contributors cannot control third-party usage and neither participate in nor endorse unlawful activity.",
        "四、开源协议与无担保声明" to "4. License and No Warranty",
        "本软件依据 GNU General Public License v3.0 协议以现状形式开源分发，不附带任何明示或暗示的适用性、可靠性或无侵权担保。在适用法律允许的最大范围内，作者与贡献者不对因使用或无法使用本软件造成的任何直接、间接或附带损失承担赔偿责任。" to "This software is distributed AS IS under the GNU General Public License v3.0 without warranties of any kind, express or implied, including fitness for a particular purpose or non-infringement. To the maximum extent permitted by law, authors and contributors are not liable for any direct, indirect, or consequential damages.",
        "五、确认与生效" to "5. Acceptance",
        "点击「同意并继续」即表示您已充分阅读、理解并自愿遵守上述全部条款；若您不同意任何内容，请点击「不同意并退出」并立即移除本软件。" to "Tapping Agree & Continue confirms that you have read, understood, and agree to these terms. If you disagree, tap Decline & Exit and remove the software.",
        "不同意并退出" to "Decline & Exit",
        "同意并继续" to "Agree & Continue",
        "开源内存分析与调试工具，遵循 GNU General Public License v3.0 协议发布。" to "Open-source memory analysis and debugging tool licensed under GNU General Public License v3.0.",
        "项目地址" to "Repository",
        "获取源码、版本发布与问题反馈" to "Source code, releases and issue tracker",
        "参考来源" to "Reference",
        "界面布局与交互设计灵感源自 AGameGuardian 优秀的界面设计。" to "Interface layout and interaction design drew inspiration from AGameGuardian's clean UI.",
        "点击打开或复制" to "Tap to open or copy",
        "当前目标" to "Current Target",
        "悬浮球已在屏幕显示" to "Floating ball is active on screen",
        "点击上方按钮启动悬浮球" to "Tap the button above to start floating ball",
        "悬浮球服务运行中 · 短按打开面板 / 长按调节变速" to "Overlay active · Tap to open panel",
        "新一代高性能内存调试工具" to "Next-gen high-performance memory debugger",
        "运行环境与权限" to "Environment & Permissions",
        "悬浮窗权限" to "Overlay Permission",
        "已授予系统悬浮窗权限，支持跨应用显示" to "System overlay permission granted",
        "点击前往系统设置授予悬浮窗权限" to "Tap to grant overlay permission in system settings",
        "已就绪" to "Ready",
        "去授权" to "Grant",
        "工作模式" to "Working Mode",
        "软件快捷入口" to "Quick Shortcuts",
        "主题与外观设置" to "Theme & Appearance",
        "语言 (Language)" to "Language",
        "使用帮助与语法指南" to "Help & Syntax Guide",
        "了解精确搜索、模糊搜索、联合搜索与批量修改用法" to "Learn exact, fuzzy, group search & batch editing",
        "关于软件" to "About App",
        "GGNext v0.6 · 查看软件信息与更新日志" to "GGNext v0.6 · App info & changelog",
        "管理软件语言、主题配色、背景透明度、页面屏幕占比与关于信息" to "Manage language, theme, opacity, screen ratio & about info",
        "语言与区域" to "Language & Region",
        "切换" to "Switch",
        "主题与外观" to "Theme & Appearance",
        "主题配色方案" to "Color Theme Preset",
        "深色模式" to "Dark Mode",
        "跟随系统" to "System",
        "深色" to "Dark",
        "浅色" to "Light",
        // 16 Theme Presets
        "玫瑰" to "Rose",
        "青翠" to "Green",
        "天青" to "Cyan",
        "蔚蓝" to "Blue",
        "紫罗兰" to "Violet",
        "琥珀" to "Amber",
        "赤红" to "Red",
        "中性" to "Mono",
        "珊瑚" to "Coral",
        "祖母绿" to "Emerald",
        "靛蓝" to "Indigo",
        "浆果" to "Berry",
        "松石" to "Teal",
        "暖沙" to "Oat",
        "青柠" to "Lime",
        "岩灰" to "Slate",
        "Android 12+ 动态壁纸取色" to "Android 12+ Dynamic Wallpaper Color",
        "动态取色" to "Dynamic Color",
        "Android 12+ 从壁纸提取配色" to "Extract palette from wallpaper on Android 12+",
        "需要 Android 12 及以上" to "Requires Android 12 or above",
        "背景透明度" to "Background Opacity",
        "页面屏幕占比" to "Screen Ratio",
        "界面字体缩放" to "Font Scale",
        "弹窗圆角半径" to "Dialog Corner Radius",
        "伪弹窗圆角" to "Dialog Corner Radius",
        "悬浮球尺寸" to "Floating Ball Size",
        "列表入场与骨架屏动画" to "List Enter & Skeleton Animation",
        "关于与支持" to "About & Support",
        "关于 GGNext" to "About GGNext",
        "版本 v0.6 · 架构与环境信息" to "Version v0.6 · Architecture & environment",
        "更新日志" to "Changelog",
        "查看 v0.6 版本优化与修复详情" to "View v0.6 optimizations & bug fixes",
        "使用帮助" to "User Guide",
        "搜索语法与功能说明" to "Search syntax & feature reference",
        "软件运行日志" to "Runtime Log",
        "查看内部状态与调试日志" to "View internal state & debug logs",
        "恢复软件默认设置" to "Reset App Settings",
        "重置主题、透明度、屏幕占比、字号、语言等软件偏好设置" to "Reset theme, opacity, screen ratio, font size & language",
        "通用设置" to "General Settings",
        "通用与界面设置" to "General & UI Settings",
        "内存设置" to "Memory Settings",
        "操作与诊断" to "Actions & Diagnostics",
        "自动暂停游戏" to "Autopause game",
        "冻结间隔 (毫秒)" to "Freeze interval (ms)",
        "进程选择" to "Select process",
        "未选择进程，点击选择" to "No process selected, tap to select",
        "未选择进程 (点击选择)" to "No process selected (tap to select)",
        "内存区域" to "Memory ranges",
        "未选择" to "None",
        "搜索结果过滤器" to "Search result filter",
        "数值显示格式" to "Value format",
        "变速倍率设置" to "Speedhack settings",
        "工具栏顺序与增删" to "Toolbar order & buttons",
        "调整搜索列表 / 保存列表 / 内存页工具栏图标" to "Customize toolbar icons for Search / Saved / Memory tabs",
        "外观与字体缩放" to "Appearance & font scale",
        "清除输入历史" to "Clear input history",
        "运行日志" to "Runtime log",
        "查看引擎与附加记录" to "View engine & attach logs",
        "帮助文档" to "Help documentation",
        "查看搜索语法与功能说明" to "View search syntax & feature guide",
        "关于" to "About",
        "搜索列表" to "Search",
        "保存列表" to "Saved",
        "内存编辑器" to "Memory",
        "内存" to "Memory",
        "未选择进程。\n点击进程栏选择目标。" to "No process selected.\nTap the process bar to select a target.",
        "打开搜索面板开始。" to "Open the search panel to start.",
        "保存列表为空。\n在搜索列表中勾选后按「保存」加入。" to "Saved list is empty.\nCheck items in Search list and tap Save.",
        "内存编辑器\n使用工具栏「转到地址」加载内存视图。" to "Memory Editor\nUse 'Go to address' on the toolbar to load memory.",
        "上一页" to "Prev",
        "下一页" to "Next",
        "定址" to "Goto",
        "刷新" to "Refresh",
        "精确搜索" to "Exact Search",
        "支持单值、范围 (A~B)、联合 (A;B:512)、异或 (AXK) 与 HEX" to "Supports single, range (A~B), group (A;B:512), XOR & HEX",
        "更多" to "More",
        "收起" to "Less",
        "键盘" to "Kbd",
        "= 等于" to "= Equal",
        "≠ 不等于" to "≠ Not equal",
        "≥ 大于或等于" to "≥ Greater or equal",
        "≤ 小于或等于" to "≤ Less or equal",
        "地址蒙版" to "Address mask",
        "搜索范围：在全部内存中" to "Range: All memory",
        "搜索范围：仅在附近内存中" to "Range: Nearby memory only",
        "加密值搜索" to "Encrypted value",
        "改善搜索" to "Refine",
        "新搜索" to "New search",
        "模糊搜索" to "Fuzzy Search",
        "选择要采样的数值类型，点击下方「搜索」按钮进行初始内存采样：" to "Select the value type to sample and tap 'Search' below:",
        "取消" to "Cancel",
        "搜索" to "Search",
        "值是否发生了改变?" to "How has the value changed?",
        "无变化" to "Unchanged",
        "有变化" to "Changed",
        "增加了" to "Increased",
        "减少了" to "Decreased",
        "只有第N个结果: " to "Only N-th result: ",
        "最大显示记录: " to "Max shown records: ",
        "地址:  ≥" to "Address: ≥",
        "地址:  ≤" to "Address: ≤",
        "数值:  ≥" to "Value: ≥",
        "数值:  ≤" to "Value: ≤",
        "类型: " to "Type: ",
        "指针: " to "Pointer: ",
        "点击选择" to "Tap to pick",
        "重置" to "Reset",
        "应用" to "Apply",
        "完成" to "Done",
        "确定" to "OK",
        "保存" to "Save",
        "返回" to "Back",
        "单项修改" to "Edit Value",
        "批量修改" to "Batch Edit",
        "输入修改值…" to "Enter value…",
        "备注" to "Note",
        "描述（可选）" to "Description (optional)",
        "冻结并保存" to "Freeze & save",
        "冻结模式" to "Freeze Mode",
        "正常冻结" to "Normal",
        "递增" to "Increment",
        "递减" to "Decrement",
        "范围钳制" to "Range",
        "范围下限" to "Min value",
        "范围上限" to "Max value",
        "加到值内" to "Add to value, do not replace",
        "可选增量步进值" to "Optional step increment",
        "值列表" to "Values",
        "修改" to "Apply",
        "正在写入..." to "Writing...",
        "选择数据类型" to "Select Data Type",
        "更改地址 / 类型" to "Change Address / Type",
        "批量更改类型" to "Batch Change Type",
        "地址" to "Address",
        "选择新类型" to "Select new type",
        "应用到全部选中项" to "Apply to all selected",
        "数值格式" to "Value Format",
        "已保存列表显示格式" to "Display format for saved list",
        "搜索列表与内存页显示格式" to "Display format for search list & memory",
        "默认" to "Default",
        "偏移量 & 异或计算" to "Offset & XOR Calculator",
        "长按任意计算出的偏移量、目标地址或异或值即可复制" to "Long-press any calculated offset, target address or XOR value to copy",
        "基址 (HEX)" to "Base (HEX)",
        "偏移 (HEX)" to "Offset (HEX)",
        "计算偏移:" to "Offset:",
        "目标地址:" to "Target:",
        "异或结果:" to "XOR:",
        "已勾选地址两两偏移 & 异或:" to "Checked addresses pairwise Offset & XOR:",
        "复制偏移" to "Copy Offset",
        "跳转到目标地址" to "Go to Target",
        "移除" to "Remove",
        "转到地址" to "Go to Address",
        "输入十六进制内存地址定位并高亮目标地址" to "Enter hex memory address to locate and highlight",
        "目标地址 (HEX)" to "Target (HEX)",
        "转到" to "Go to",
        "模块列表" to "Module List",
        "正在加载内存映射..." to "Loading memory maps...",
        "执行脚本" to "Script Manager",
        "从外部存储执行 Lua 脚本。" to "Manage local Lua script paths.",
        "输入可读取的本地脚本路径。" to "Enter a readable local script path.",
        "当前版本暂未内置 Lua 执行引擎，仅支持脚本路径管理与编辑" to "Lua VM is not bundled in this build; script path management and notes only",
        "暂未实现" to "Unavailable",
        "最近执行的脚本:" to "Recent Scripts:",
        "浏览" to "Browse",
        "运行" to "Execute",
        "浏览并选择文件夹" to "Select Folder",
        "文件浏览器" to "File Browser",
        "上一级" to "Up",
        "新建目录" to "New Folder",
        "根目录 /" to "Root /",
        "搜索文件名或后缀…" to "Search file name or ext…",
        "全部" to "All",
        "选择当前文件夹" to "Select This Folder",
        "复制内存" to "Copy Memory",
        "复制起始地址与结束地址之间区域的内存数据" to "Copy memory between start address and end address",
        "起始地址" to "From",
        "结束地址" to "To",
        "结束地址必须大于或等于起始地址" to "End address must be >= start address",
        "复制" to "Copy",
        "导出内存" to "Dump Memory",
        "将起始地址与结束地址之间的内存区域导出到文件" to "Export memory region between start and end address to file",
        "路径" to "Path",
        "导出" to "Dump",
        "分配内存页" to "Allocate Memory Page",
        "当前版本暂未实现跨进程内存分配" to "Cross-process memory page allocation (ptrace mmap) is not implemented in this version",
        "也许可读" to "May read",
        "也许可写" to "May write",
        "也许可执行" to "May execute",
        "地址:" to "Address:",
        "保存/载入列表文件" to "List File Manager",
        "从文件载入列表" to "Load List from File",
        "保存列表到文件" to "Save List to File",
        "导出选定项到文件" to "Export Selected to File",
        "载入" to "Load",
        "保存文件" to "Save File",
        "导出选中" to "Export Selected",
        "输入文件路径或点击右侧「浏览」从文件浏览器中选择" to "Enter file path or tap 'Browse' to pick from file browser",
        "游戏变速" to "Speedhack",
        "当前版本暂未实现底层变速 Hook，倍率仅作占位提示" to "Low-level clock speedhack hook is not implemented in this version; value is placeholder only",
        "选择语言 (Language)" to "Select Language",
        "输入历史" to "Input History",
        "无历史记录" to "No history",
        "清空" to "Clear",
        "暂无日志" to "No logs yet",
        "悬浮窗管理" to "Floating Window",
        "关闭悬浮窗" to "Stop Overlay",
        "进程列表" to "Process List",
        "选择要附加的进程" to "Select a process to attach",
        "搜索名称 / 包名 / pid" to "Search name / package / pid",
        "用户" to "User",
        "系统" to "System",
        "优先级" to "Order",
        "大小" to "Size",
        "选择内存范围" to "Select Memory Ranges",
        "未选择进程" to "No process selected",
        "工具栏顺序" to "Toolbar Order",
        "长按行上下拖动排序 / 下方可增删工具" to "Long-press and drag up/down to reorder / add or remove below",
        "增删工具" to "Add/Remove",
        "点击勾选或移除该页面的工具栏图标" to "Tap to enable or disable toolbar icons for this tab",
        "返回排序" to "Back to Order",
        "外观" to "Appearance",
        "主题配色、深浅色模式、背景透明度与屏幕占比" to "Theme color, dark/light mode, opacity & screen ratio",
        "主题色" to "Theme Color",
        "地址蒙版搜索" to "Address Mask Search",
        "支持使用 ? 通配符匹配特定偏移规律的内存地址" to "Match addresses by bitmask or '?' wildcards",
        "蒙版:" to "Mask:",
        "改善结果" to "Refine",
        "新蒙版搜索" to "New Mask Search",
        "指针搜索" to "Pointer Search",
        "查找指向目标地址及最大偏移范围内的指针链" to "Find pointers to target address within max offset",
        "最大偏移:" to "Max offset:",
        "开始指针搜索" to "Search Pointers",
        "偏移量计算器" to "Offset calculator",
        "计算偏移、异或" to "Calculate offset, XOR",
        "搜索附近内存" to "Search nearby memory",
        "附近" to "Nearby",
        "附近:" to "Nearby:",
        "之前" to "Before",
        "后" to "After",
        "距离:" to "Distance:",
        "偏移量:" to "Offset:",
        "结果:" to "Result:",
        "终止此进程" to "Kill Target Process",
        "退出" to "Exit GGNext",
        "全选" to "Select All",
        "粘贴" to "Paste",
        "剪切" to "Cut",
        "反选" to "Invert",
        "清空选择" to "Clear Selection",
        "删除" to "Delete",
        "编辑" to "Edit",
        "过滤" to "Filter",
        "脚本" to "Scripts",
        "文件" to "Files",
        "恢复" to "Revert",
        "导出选中" to "Export",
        "复制地址" to "Copy Address",
        "更改类型" to "Change Type",
        "历史" to "History",
        "搜索" to "Search",
        "模糊" to "Fuzzy",
        "蒙版" to "Mask",
        "指针" to "Pointer",
        "计算" to "Offset",
        "暂停" to "Pause",
        "后退" to "Back",
        "前进" to "Forward",
        "数值格式" to "Format",
        "内存区域" to "Regions",
        "菜单" to "More",
        "变速" to "Speed",
        "载入为结果" to "Load as Results",
        "保留有变化" to "Keep Changed",
        "历史记录条数限制" to "History Limit",
        "工具栏按钮" to "Toolbar Buttons",
        "悬浮窗大小" to "Floating Ball Size",
        "显示日志" to "Show Logs",
        "重置设置" to "Reset Settings",
        "帮助" to "Help",
        "操作" to "Actions",
        "其余选项" to "Other",
        "是" to "Yes",
        "否" to "No",
        "HEX 逆序" to "Reverse HEX",
        "字符串" to "String",
        "Java 字符串" to "Java String",
        // Toasts & Parser errors
        "已取消搜索" to "Search cancelled",
        "目标进程已退出或重启，请重新附加" to "Target process exited or restarted, please re-attach",
        "请先选择进程" to "Please select a process first",
        "请先附加目标进程" to "Please attach to a target process first",
        "请先成功附加目标进程（当前无内存读取权限）" to "Please attach to a target process first (no memory read permission)",
        "请输入搜索条件" to "Please enter a search query",
        "请输入要搜索的值" to "Please enter a value to search",
        "文本不能为空" to "Text cannot be empty",
        "数值为空" to "Value is empty",
        "范围只能有两个边界值" to "Range must have exactly two bounds",
        "区域大小必须在 2 - 65,536 之间" to "Group span must be between 2 and 65,536",
        "联合搜索至少需要两个数值" to "Group search requires at least 2 values",
        "联合搜索最多 64 个数值" to "Group search supports at most 64 values",
        "无项目被选中" to "No items selected",
        "没有可修改的项目" to "No items to edit",
        "请输入修改数值" to "Please enter a value",
        "修改值无效" to "Invalid edit value",
        "输入格式错误" to "Invalid input format",
        "已恢复默认设置" to "Default settings restored",
        "已保存内存范围设置" to "Memory range settings saved",
        "已应用筛选" to "Filter applied",
        "已重置筛选条件" to "Filter reset",
        "已清空历史" to "History cleared",
        "已刷新实时内存数据" to "Live memory values refreshed",
        "已切换至 Root 模式" to "Switched to Root mode",
        "已切换至 Root 模式，未检测到 Root 授权" to "Switched to Root mode (Root access not detected)",
        "已切换至免 Root 模式" to "Switched to No-Root mode",
        "Root 权限已获取" to "Root access granted",
        "未检测到 Root 权限，请在管理器中授权或切换到免 Root 模式" to "Root not detected; grant access or switch to No-Root mode",
        "请输入有效的文件路径" to "Please enter a valid file path",
        "文件不存在" to "File does not exist",
        "文件中没有可载入的有效条目" to "No valid entries found in file",
        "保存列表为空，无可保存项目" to "Saved list is empty",
        "结束地址必须大于起始地址" to "End address must be greater than start address",
        "字节数必须大于 0" to "Byte count must be greater than 0",
        "复制内存失败，请检查地址范围是否可读写" to "Copy memory failed; check readable/writable range",
        "固定工具栏至少需保留一项" to "Pinned toolbar must keep at least 1 item",
        "未选择目标进程" to "No target process selected",
        "修改器已退出" to "GGNext exited",
        "已启动悬浮球，短按打开面板" to "Floating ball started; tap to open panel",
        "已关闭悬浮窗" to "Floating overlay stopped",
        "未发现数值发生变化的项目" to "No changed items found",
        "请先勾选要载入为搜索结果的项目" to "Select items to load as search results first",
        "正在读取..." to "Reading...",
        "调试" to "Debug",
        "调试功能将在后续版本中提供" to "Debugging features will be available in a future update",
        "扩展" to "Extensions",
        "扩展插件" to "Extensions",
        "扩展插件系统将在后续版本中提供" to "Extension plugin system will be available in a future update",
        "常规" to "General",
        "主题" to "Theme",
        "字体" to "Font",
        "系统默认" to "System Default",
        "自定义字体" to "Custom Font",
        "导入 TTF" to "Import TTF",
        "恢复默认" to "Reset",
        "选择 TTF 字体文件" to "Select TTF Font File",
        "默认使用系统字体，可导入本地 .ttf 字体文件并同步至主页与悬浮窗" to "Uses system font by default. Import a local .ttf font to sync across Home and Overlay.",
        "已恢复系统默认字体" to "Restored system default font",
        "字体导入失败：仅支持有效的 .ttf / .otf / .ttc 字体文件" to "Font import failed: only valid .ttf / .otf / .ttc font files are supported",
        "字体文件无效或已损坏" to "Invalid or corrupted font file",
        "字体文件不存在" to "Font file does not exist",
        "改善" to "Refine",
        "外观与主题" to "Appearance & Theme",
        "主题配色、字体、深浅色模式、背景透明度与屏幕占比" to "Theme color, font, dark/light mode, opacity & screen ratio",
        "悬浮窗服务" to "Overlay Service",
        "在屏幕显示可拖拽悬浮球" to "Display a draggable floating ball on screen",
        "短按悬浮球打开面板，长按调节变速" to "Tap the floating ball to open panel, hold for speed",
        "GGNext v0.6 · 点击展开修改器面板" to "GGNext v0.6 · Tap to open modifier panel",
        "GGNext v0.6 · 点击启动悬浮球服务" to "GGNext v0.6 · Tap to start overlay service",
        "Root · 已授权" to "Root · Granted",
        "Root · 未授权，点击检测" to "Root · Not granted, tap to check",
        "免 Root · 共享 UID" to "No Root · Shared UID",
        "版本 v0.6、项目地址与开源许可" to "Version v0.6, repository & open-source license",
        "重置主题、字体、透明度、屏幕占比与语言设置" to "Reset theme, font, opacity, screen ratio & language",
        "权限与环境" to "Permissions & Environment",
        "所有文件访问权限" to "All Files Access",
        "文件浏览器与脚本路径可访问完整外部存储" to "Full external storage access for file browser & scripts",
        "授予后可在文件浏览器中完整查看所有文件" to "Grant to view all files in the built-in file browser",
        "已授予所有文件访问权限" to "All files access permission granted",
        "Root 权限检测" to "Root Access Check",
        "已获取超级用户权限，可直接读写进程内存" to "Superuser access granted for direct memory read/write",
        "未检测到 Root 授权，点击重新检测" to "Root access not detected, tap to check again",
        "检测" to "Check",
        "运行状态" to "Runtime Status",
        "当前目标进程" to "Current Target Process",
        "未附加" to "Not Attached",
        "已启用内存区域" to "Enabled Memory Regions",
        "GGNext v0.6 · 点击启动悬浮球" to "GGNext v0.6 · Tap to start floating ball",
        "停止" to "Stop",
        "启动" to "Start",
        "支持与诊断" to "Support & Diagnostics",
        "搜索语法、联合搜索与功能说明" to "Search syntax, group search & feature guide",
        "关于" to "About",
        "特别鸣谢" to "Special Thanks",
        "致谢启发本项目设计的优秀工具" to "Credits to projects that inspired GGNext",
        "版本 v0.6、官方群组与开源许可" to "Version v0.6, official group & license",
        "色彩与深浅模式" to "Colors & Dark Mode",
        "字体管理" to "Font Manager",
        "已管理字体" to "Managed Fonts",
        "导入字体" to "Import Font",
        "导入本地 .ttf / .otf 字体后可随时切换或删除，同步生效于主页与悬浮窗" to "Import local .ttf / .otf fonts to switch or delete anytime across Home & Overlay",
        "不额外加载外部字体文件" to "Uses system font without loading external files",
        "删除字体" to "Delete Font",
        "悬浮窗与界面尺寸" to "Overlay & Layout Sizing",
        "暂无日志可复制" to "No logs to copy",
        "已复制运行日志" to "Logs copied",
        "GGNext 的设计与成长离不开以下优秀项目与先驱工具的启发，谨致以诚挚谢意。" to "GGNext's design and growth were inspired by the following outstanding projects and pioneers.",
        "界面与交互灵感" to "UI & Interaction Inspiration",
        "GGNext 的整体视觉层次、布局排布与多项交互细节，参考并致敬了 AGameGuardian 优秀的界面设计理念。" to "GGNext's visual hierarchy, layout structure, and interaction details draw inspiration from AGameGuardian's excellent interface design.",
        "经典内存分析工具先驱" to "Classic Memory Analysis Pioneer",
        "致敬 Android 平台经典的内存分析与修改工具 GameGuardian。其开创性的搜索语法、联合搜索范式与内存浏览体系，为本项目奠定了重要参考与灵感。" to "A tribute to GameGuardian, the classic Android memory analysis tool whose pioneering search syntax, group search paradigm, and memory viewer inspired this project.",
        "点击卡片即可前往对应项目主页" to "Tap any card to visit its homepage",
        "无法打开浏览器，已复制链接" to "Could not open browser; link copied",
        "无法直接打开浏览器，已复制链接" to "Could not open browser; link copied",
        "开源 Android 内存分析与调试工具，采用 Jetpack Compose 与 C++20 原生引擎构建，遵循 GNU General Public License v3.0 协议发布。" to "Open-source Android memory analysis and debugging tool built with Jetpack Compose and a native C++20 engine under GNU GPL v3.0.",
        "社区与项目" to "Community & Project",
        "官方群组" to "Official Group",
        "QQ 群" to "QQ Group",
        "交流反馈与更新动态" to "Feedback, discussion & release updates",
        "加入" to "Join",
        "开源仓库" to "Open Source Repository",
        "GGNext GitHub · 查看源码、提交 Issue 与获取更新" to "GGNext GitHub · Source code, issues & releases",
        "查看源代码、提交 Issue 与获取版本发布" to "View source code, submit issues & get releases",
        "加入交流反馈、获取更新动态与使用技巧" to "Join for feedback, updates & usage tips",
        "点击加入或复制" to "Tap to join or copy",
        "点击前往" to "Open",
        "点击访问" to "Visit",
        "开源协议" to "License",
        "当前环境" to "Current Environment",
        "当前字体" to "Current Font",
        "返回上一级" to "Parent directory",
        "文件夹" to "Folder",
        "正在读取目录…" to "Loading directory...",
        "未找到匹配的文件或文件夹" to "No matching files or folders",
        "当前目录为空或无访问权限" to "Directory is empty or inaccessible",
        "显示隐藏文件" to "Show hidden files",
        "刷新目录" to "Refresh directory",
        "未授予所有文件访问权限，可能无法显示部分文件 · 点击授权" to "All files access not granted; some files may be hidden · Tap to grant",
        "按顺序" to "Ordered",
        "群组大小" to "Group Size",
        "群组距离" to "Group Distance",
        "设置联合搜索最大跨度（默认 512）" to "Set max group search span (default 512)"
    )

    private fun toEnglish(text: String): String {
        enMap[text]?.let { return it }
        when {
            text.startsWith("已选 ") -> return "Selected " + text.removePrefix("已选 ")
            text.startsWith("修改选中 ") -> return "Edit " + text.removePrefix("修改选中 ") + " selected"
            text.startsWith("批量更改类型（已选 ") ->
                return text.replace("批量更改类型（已选 ", "Batch Change Type (").replace(" 项）", " selected)")
            text.startsWith("当前：") -> return "Current: " + toEnglish(text.removePrefix("当前："))
            text.startsWith("当前预设：") -> return "Preset: " + toEnglish(text.removePrefix("当前预设："))
            text.startsWith("当前主题：") -> return "Theme: " + toEnglish(text.removePrefix("当前主题："))
            text.startsWith("当前共有 ") -> return text.replace("当前共有 ", "Total ").replace(" 条输入记录", " history records")
            text.startsWith("最大显示 ") -> return text.replace("最大显示 ", "Max ").replace(" 条 · 地址/数值/类型筛选", " · Address/Value/Type filter")
            text.startsWith("搜索范围：指定地址区间") -> return text.replace("搜索范围：指定地址区间", "Range: Custom")
            text.startsWith("值列表 [") -> return text.replace("值列表 [", "Value Action [")
            text.startsWith("编辑此地址数值") -> return text.replace("编辑此地址数值", "Edit value")
            text.startsWith("转到此地址：") -> return text.replace("转到此地址：", "Go to address: ")
            text.startsWith("跳转到指针地址：") -> return text.replace("跳转到指针地址：", "Go to pointer: ")
            text.startsWith("复制地址：") -> return text.replace("复制地址：", "Copy address: ")
            text.startsWith("复制十进制值：") -> return text.replace("复制十进制值：", "Copy decimal: ")
            text.startsWith("复制十六进制：") -> return text.replace("复制十六进制：", "Copy HEX: ")
            text.startsWith("复制反向十六进制：") -> return text.replace("复制反向十六进制：", "Copy reverse HEX: ")
            text.startsWith("恢复并移除所选") -> return text.replace("恢复并移除所选", "Revert & remove selected")
            text.startsWith("移除所选") -> return text.replace("移除所选", "Remove selected")
            text == "移除全部列表项" -> return "Remove all items"
            text.startsWith("文件夹 · ") -> return text.replace("文件夹 · ", "Folder · ").replace(" 项", " items")
            text.startsWith("选中区域: ") -> return text.replace("选中区域: ", "Region: ").replace("共 ", "").replace(" 字节", " bytes")
            text.startsWith("导出区间: ") -> return text.replace("导出区间: ", "Dump: ").replace(" 字节", " bytes")
            text.startsWith("最终合成蒙版: ") -> return text.replace("最终合成蒙版: ", "Resultant mask: ")
            text.startsWith("命中 ") -> return "Found " + text.removePrefix("命中 ")
            text.startsWith("找到 ") -> return text.replace("找到 ", "Found ").replace(" 条结果", " results")
            text.startsWith("已完成初始模糊采样，共 ") -> return text.replace("已完成初始模糊采样，共 ", "Initial fuzzy snapshot: ").replace(" 项", " items")
            text.startsWith("模糊筛选 ") -> return text.replace("模糊筛选 ", "Fuzzy ").replace("：剩余 ", ": ").replace(" 项", " left")
            text.startsWith("蒙版搜索找到 ") -> return text.replace("蒙版搜索找到 ", "Mask search found ").replace(" 项", " items")
            text.startsWith("指针搜索找到 ") -> return text.replace("指针搜索找到 ", "Pointer search found ").replace(" 条结果", " results")
            text.startsWith("已附加到 ") -> return "Attached to " + text.removePrefix("已附加到 ")
            text.startsWith("当前已附加到 ") -> return "Already attached to " + text.removePrefix("当前已附加到 ")
            text.startsWith("附加 ") -> return text.replace("附加 ", "Attach ").replace(" 失败：", " failed: ")
            text.startsWith("已暂停目标进程 ") -> return "Paused " + text.removePrefix("已暂停目标进程 ")
            text.startsWith("已恢复目标进程 ") -> return "Resumed " + text.removePrefix("已恢复目标进程 ")
            text.startsWith("已终止目标进程 ") -> return "Terminated " + text.removePrefix("已终止目标进程 ")
            text.startsWith("已保存 ") -> return text.replace("已保存 ", "Saved ").replace(" 项到保存列表", " items to Saved list").replace(" 项到 ", " items to ")
            text.startsWith("已从 ") -> return text.replace("已从 ", "Loaded from ").replace(" 载入 ", ": ").replace(" 项", " items")
            text.startsWith("已批量修改 ") -> return text.replace("已批量修改 ", "Batch edited ").replace(" 项", " items")
            text.startsWith("已修改地址 ") -> return "Edited address " + text.removePrefix("已修改地址 ")
            text.startsWith("已批量更改 ") -> return text.replace("已批量更改 ", "Changed type of ").replace(" 项的类型为 ", " items to ")
            text.startsWith("已更新地址为 ") -> return "Updated address to " + text.removePrefix("已更新地址为 ")
            text.startsWith("已恢复 ") -> return text.replace("已恢复 ", "Reverted ").replace(" 项为先前数值", " items to previous values")
            text.startsWith("已将 ") -> return text.replace("已将 ", "Loaded ").replace(" 项载入为搜索结果", " items as search results")
            text.startsWith("已保留 ") -> return text.replace("已保留 ", "Kept ").replace(" 个有变化的结果", " changed results")
            text.startsWith("新搜索: ") -> return "New search: " + text.removePrefix("新搜索: ")
            text.startsWith("改善: ") -> return "Refine: " + text.removePrefix("改善: ")
            text.startsWith("模糊初始采样中") -> return "Fuzzy initial snapshot..."
            text.startsWith("模糊筛选 (") -> return text.replace("模糊筛选 (", "Fuzzy (")
            text.startsWith("地址蒙版搜索中") -> return "Address mask searching..."
            text.startsWith("指针链搜索中") -> return "Pointer searching..."
            text.startsWith("读取内存页") -> return "Reading memory page"
            text.startsWith("导出内存中") -> return "Dumping memory"
            text.startsWith("已导出内存区间到 ") -> return "Dumped memory to " + text.removePrefix("已导出内存区间到 ")
            text.startsWith("导出内存失败") -> return "Memory dump failed"
            text.startsWith("文件读写失败: ") -> return "File I/O error: " + text.removePrefix("文件读写失败: ")
            text.startsWith("已成功复制 ") -> return text.replace("已成功复制 ", "Copied ").replace(" 字节到 ", " bytes to ")
            text.startsWith("无效的反向十六进制: ") -> return "Invalid reverse HEX: " + text.removePrefix("无效的反向十六进制: ")
            text.startsWith("无效的十六进制: ") -> return "Invalid HEX: " + text.removePrefix("无效的十六进制: ")
            text.startsWith("无效的浮点数: ") -> return "Invalid Float: " + text.removePrefix("无效的浮点数: ")
            text.startsWith("无效的双精度数: ") -> return "Invalid Double: " + text.removePrefix("无效的双精度数: ")
            text.startsWith("无效的数值: ") -> return "Invalid number: " + text.removePrefix("无效的数值: ")
            text.startsWith("区域大小无效: ") -> return "Invalid span: " + text.removePrefix("区域大小无效: ")
            text.startsWith("Byte 取值范围为") -> return text.replace("Byte 取值范围为 -128 ~ 255，当前: ", "Byte range is -128..255, got: ")
            text.startsWith("Word 取值范围为") -> return text.replace("Word 取值范围为 -32,768 ~ 65,535，当前: ", "Word range is -32,768..65,535, got: ")
            text.startsWith("Dword 取值范围为") -> return text.replace("Dword 取值范围为 -2,147,483,648 ~ 4,294,967,295，当前: ", "Dword range is -2,147,483,648..4,294,967,295, got: ")
            text == "硬件断点" -> return "Hardware Breakpoint"
            text == "硬件断点调试器" -> return "Hardware Breakpoint Debugger"
            text == "设置硬件断点" -> return "Set Hardware Breakpoint"
            text == "添加硬件断点" -> return "Add Hardware Breakpoint"
            text == "添加断点" -> return "Add Breakpoint"
            text == "断点地址 (HEX)" -> return "Breakpoint Address (HEX)"
            text == "断点类型" -> return "Breakpoint Type"
            text == "监视大小 (字节)" -> return "Watch Size (Bytes)"
            text == "调试后端模式" -> return "Debugger Backend Mode"
            text == "只读" -> return "Read"
            text == "只写" -> return "Write"
            text == "读写" -> return "R/W"
            text == "执行" -> return "Exec"
            text == "字节" -> return "B"
            text == "活动" -> return "Active"
            text == "运行中" -> return "Running"
            text == "已暂停" -> return "Paused"
            text == "已停止" -> return "Stopped"
            text == "目标" -> return "PID"
            text == "线程" -> return "Threads"
            text == "架构" -> return "Arch"
            text == "寄存器" -> return "Registers"
            text == "浮点" -> return "FP"
            text == "回溯" -> return "Backtrace"
            text == "确认添加" -> return "Add"
            text == "写入汇编" -> return "Write ASM"
            text == "写入汇编指令" -> return "Write Assembly"
            text == "写入地址 (HEX)" -> return "Target Address (HEX)"
            text == "指令集架构" -> return "Architecture"
            text == "机器码预览" -> return "Machine Code Preview"
            text == "汇编引擎扩展" -> return "Assembly Engine Pack"
            text == "Keystone / Capstone 引擎包" -> return "Keystone / Capstone Pack"
            text == "已就绪" -> return "Ready"
            text == "部分加载" -> return "Partial"
            text == "未导入" -> return "Not Imported"
            text == "导入压缩包 (.zip)" -> return "Import Pack (.zip)"
            text == "重新导入压缩包" -> return "Re-import Pack"
            text == "移除" -> return "Remove"
            text == "应用变速" -> return "Apply Speed"
            text == "重置 1.0x" -> return "Reset 1.0x"
            text == "分配" -> return "Allocate"
            text == "采集寄存器与调用链回溯" -> return "Capture Registers & Backtrace"
            text == "命中断点时自动记录完整寄存器状态及栈回溯" -> return "Automatically record GPR/FP registers and callstack on hit"
        }
        return text
    }
}
