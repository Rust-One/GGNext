package com.littlecodesauce.ggnext.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.AppStrings
import com.littlecodesauce.ggnext.core.FileBrowserTarget
import com.littlecodesauce.ggnext.core.HomeTab
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.service.OverlayManager
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.screen.RowDivider
import com.littlecodesauce.ggnext.ui.screen.SliderRow
import com.littlecodesauce.ggnext.ui.screen.SwitchRow
import com.littlecodesauce.ggnext.ui.theme.AppFontCache
import com.littlecodesauce.ggnext.ui.theme.DarkMode
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.ThemePreset
import kotlin.math.roundToInt

private enum class HomeSubPage {
    NONE,
    APPEARANCE,
    HELP,
    LOG,
    THANKS,
    ABOUT
}

/**
 * 重构后的软件主页（Material 3 分组卡片 + 独立子页面导航 + 底部三页签：主页 / 扩展 / 设置）
 */
@Composable
fun HomeScreen(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var subPage by remember { mutableStateOf(HomeSubPage.NONE) }

    BackHandler(enabled = subPage != HomeSubPage.NONE && ui.sheet == Sheet.NONE && !ui.panelVisible) {
        subPage = HomeSubPage.NONE
    }

    BackHandler(enabled = subPage == HomeSubPage.NONE && ui.homeTab != HomeTab.HOME && ui.sheet == Sheet.NONE && !ui.panelVisible) {
        Session.selectHomeTab(HomeTab.HOME)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
    ) {
        AnimatedContent(
            targetState = subPage,
            transitionSpec = {
                if (targetState != HomeSubPage.NONE) {
                    (slideInHorizontally(tween(210)) { it / 5 } + fadeIn(tween(180)))
                        .togetherWith(fadeOut(tween(120)))
                } else {
                    fadeIn(tween(170))
                        .togetherWith(slideOutHorizontally(tween(180)) { it / 5 } + fadeOut(tween(130)))
                }
            },
            label = "homeSubPageTransition",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                HomeSubPage.NONE -> {
                    AnimatedContent(
                        targetState = ui.homeTab,
                        transitionSpec = {
                            fadeIn(tween(160)).togetherWith(fadeOut(tween(110)))
                        },
                        label = "homeTabContent",
                        modifier = Modifier.fillMaxSize()
                    ) { tab ->
                        when (tab) {
                            HomeTab.HOME -> HomeMainTab(
                                ui = ui,
                                onStartOverlay = onStartOverlay,
                                onStopOverlay = onStopOverlay
                            )
                            HomeTab.EXTENSIONS -> HomeExtensionsTab()
                            HomeTab.SETTINGS -> HomeSettingsTab(
                                ui = ui,
                                onOpenSubPage = { subPage = it }
                            )
                        }
                    }
                }
                HomeSubPage.APPEARANCE -> HomeAppearanceSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.HELP -> HomeHelpSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.LOG -> HomeLogSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.THANKS -> HomeThanksSubPage(
                    onBack = { subPage = HomeSubPage.NONE }
                )
                HomeSubPage.ABOUT -> HomeAboutSubPage(
                    ui = ui,
                    onBack = { subPage = HomeSubPage.NONE }
                )
            }
        }

        if (subPage == HomeSubPage.NONE) {
            HomeBottomNavBar(
                selectedTab = ui.homeTab,
                onSelect = { Session.selectHomeTab(it) }
            )
        }
    }
}

@Composable
private fun HomeMainTab(
    ui: Ui,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val ctx = LocalContext.current
    val hasOverlayPerm = remember(ui.floatingBallVisible, ui.panelVisible) {
        OverlayManager.canDraw(ctx)
    }
    val hasAllFilesPerm = remember(ui.floatingBallVisible, ui.panelVisible) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
    val isRunning = ui.floatingBallVisible

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("top_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GGNext",
                    color = Rose.Text,
                    fontSize = fsp(24f),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 融合式主状态卡片（上方运行状态横幅 + 启停控制 + 下方工作模式选择器）
        item("hero_card") {
            HeroStatusCard(
                ui = ui,
                isRunning = isRunning,
                onStatusClick = {
                    if (isRunning) {
                        Session.setPanelVisible(true)
                    } else {
                        onStartOverlay()
                    }
                },
                onToggleService = {
                    if (isRunning) onStopOverlay() else onStartOverlay()
                }
            )
        }

        item("perm_section_label") {
            HomeSectionTitle("权限与环境")
        }

        item("perm_card") {
            HomeGroupedCard {
                HomeNavListRow(
                    icon = Icons.Rounded.Security,
                    title = "悬浮窗权限",
                    subtitle = if (hasOverlayPerm) "已允许在其他应用上层显示" else "尚未授予系统悬浮窗权限",
                    badgeText = if (hasOverlayPerm) "已授权" else "去授权",
                    badgeColor = if (hasOverlayPerm) Rose.Green else Rose.Yellow
                ) {
                    if (hasOverlayPerm) {
                        Session.toast("系统悬浮窗权限已授予")
                    } else {
                        OverlayManager.requestPermission(ctx)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    RowDivider()
                    HomeNavListRow(
                        icon = Icons.Rounded.FolderOpen,
                        title = "所有文件访问权限",
                        subtitle = if (hasAllFilesPerm) "文件浏览器与脚本路径可访问完整外部存储" else "授予后可在文件浏览器中完整查看所有文件",
                        badgeText = if (hasAllFilesPerm) "已授权" else "去授权",
                        badgeColor = if (hasAllFilesPerm) Rose.Green else Rose.Yellow
                    ) {
                        if (hasAllFilesPerm) {
                            Session.toast("已授予所有文件访问权限")
                        } else {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                ctx.startActivity(intent)
                            }.onFailure {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    }
                }
                if (ui.workMode == WorkMode.ROOT) {
                    RowDivider()
                    HomeNavListRow(
                        icon = Icons.Rounded.Verified,
                        title = "Root 权限检测",
                        subtitle = if (ui.hasRootPermission) "已获取超级用户权限，可直接读写进程内存" else "未检测到 Root 授权，点击重新检测",
                        badgeText = if (ui.hasRootPermission) "已授权" else "检测",
                        badgeColor = if (ui.hasRootPermission) Rose.Green else Rose.Red
                    ) {
                        Session.refreshRootStatus(promptIfMissing = true)
                    }
                }
            }
        }

        item("asm_engine_section_label") {
            HomeSectionTitle("汇编引擎扩展")
        }

        item("asm_engine_card") {
            HomeAsmEnginePackCard(ui = ui)
        }

        item("status_section_label") {
            HomeSectionTitle("运行状态")
        }

        item("status_summary_card") {
            HomeGroupedCard {
                HomeInfoRow(
                    icon = Icons.Rounded.Memory,
                    title = "当前目标进程",
                    value = ui.target?.label ?: tr("未附加")
                )
                RowDivider()
                HomeInfoRow(
                    icon = Icons.Rounded.Layers,
                    title = "已启用内存区域",
                    value = "${ui.selectedRegions.size} / ${ui.regions.size}"
                )
            }
        }
    }
}

@Composable
private fun HomeAsmEnginePackCard(ui: Ui) {
    val pack = ui.asmPack
    val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null
    val statusBadge = when {
        pack.fullyReady -> "已就绪"
        pack.keystoneReady || pack.capstoneReady -> "部分加载"
        else -> "未导入"
    }
    val statusColor = when {
        pack.fullyReady -> Rose.Green
        pack.keystoneReady || pack.capstoneReady -> Rose.Yellow
        else -> Rose.TextDim
    }
    val abiText = pack.abi.ifBlank { com.littlecodesauce.ggnext.engine.AsmEngineManager.primaryAbi() }
    val detailText = when {
        pack.fullyReady -> "${pack.versionInfo.ifBlank { "Keystone + Capstone" }} · ABI: $abiText"
        pack.error.isNotBlank() -> pack.error
        else -> "独立外置压缩包（arm / arm64 / x86 / x86_64），导入后启用完整汇编与反汇编"
    }

    HomeGroupedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = null,
                tint = if (pack.fullyReady) Rose.Green else Rose.Primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("Keystone / Capstone 引擎包"),
                        color = Rose.Text,
                        fontSize = fsp(15f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tr(statusBadge),
                            color = statusColor,
                            fontSize = fsp(10.5f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr(detailText),
                    color = if (pack.error.isNotBlank() && !pack.fullyReady) Rose.Red else Rose.TextSecond,
                    fontSize = fsp(12f),
                    lineHeight = fsp(16.5f)
                )
            }
        }

        RowDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasActivityResultOwner) {
                HomeSafAsmPackPickerButton(modifier = Modifier.weight(1f), installed = pack.installed)
            } else {
                RoseButton(
                    label = if (pack.installed) "重新导入压缩包" else "导入压缩包 (.zip)",
                    filled = true,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Session.openFileBrowser(
                        initialPath = "/sdcard/Download",
                        selectDirectory = false,
                        returnSheet = Sheet.NONE,
                        target = FileBrowserTarget.ASM_PACK
                    )
                }
            }
            if (pack.installed) {
                RoseButton(
                    label = "移除",
                    modifier = Modifier.width(74.dp).height(36.dp)
                ) {
                    Session.clearAsmPack()
                }
            }
        }
    }
}

@Composable
private fun HomeSafAsmPackPickerButton(
    modifier: Modifier = Modifier,
    installed: Boolean
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Session.importAsmPackFromUri(uri)
        }
    }
    RoseButton(
        label = if (installed) "重新导入压缩包" else "导入压缩包 (.zip)",
        filled = true,
        modifier = modifier.height(36.dp)
    ) {
        runCatching {
            launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        }.onFailure {
            Session.openFileBrowser(
                initialPath = "/sdcard/Download",
                selectDirectory = false,
                returnSheet = Sheet.NONE,
                target = FileBrowserTarget.ASM_PACK
            )
        }
    }
}

@Composable
private fun HeroStatusCard(
    ui: Ui,
    isRunning: Boolean,
    onStatusClick: () -> Unit,
    onToggleService: () -> Unit
) {
    val isDark = Rose.IsDark
    val statusBannerBg by animateColorAsState(
        targetValue = when {
            isRunning && isDark -> Color(0xFF0E4A2F)
            isRunning && !isDark -> Color(0xFFDDF4E7)
            isDark -> Rose.CardHigh
            else -> Rose.ChipSelected.copy(alpha = 0.55f)
        },
        animationSpec = Motion.ColorNormal,
        label = "heroBannerBg"
    )
    val statusAccent = when {
        isRunning && isDark -> Color(0xFF8BE2B0)
        isRunning && !isDark -> Color(0xFF146C3E)
        else -> Rose.Primary
    }
    val statusSubColor = when {
        isRunning && isDark -> Color(0xFFA7DFBE)
        isRunning && !isDark -> Color(0xFF287C50)
        else -> Rose.TextSecond
    }

    var modeDropdownOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Rose.Card)
            .border(
                width = 0.8.dp,
                color = if (isRunning) statusAccent.copy(alpha = 0.25f) else Rose.Divider,
                shape = RoundedCornerShape(22.dp)
            )
    ) {
        // 上半区：运行状态横幅
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusBannerBg)
                .clickableWithTooltip(onClick = onStatusClick)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Rounded.Verified else Icons.Rounded.Layers,
                contentDescription = null,
                tint = statusAccent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr(if (isRunning) "悬浮窗运行中" else "悬浮窗未启动"),
                    color = statusAccent,
                    fontSize = fsp(16.5f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tr(
                        if (isRunning) "GGNext v0.6 · 点击展开修改器面板"
                        else "GGNext v0.6 · 点击启动悬浮球"
                    ),
                    color = statusSubColor,
                    fontSize = fsp(12.5f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(statusAccent.copy(alpha = if (isDark) 0.20f else 0.16f))
                    .border(0.8.dp, statusAccent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onToggleService)
                    .padding(horizontal = 13.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr(if (isRunning) "停止" else "启动"),
                    color = statusAccent,
                    fontSize = fsp(12.5f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 下半区：工作模式行 + 右侧圆角下拉胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithTooltip {
                    when (ui.workMode) {
                        WorkMode.ROOT -> {
                            if (!ui.hasRootPermission) {
                                Session.refreshRootStatus(promptIfMissing = true)
                            } else {
                                Session.toast("当前运行于 Root 直读模式")
                            }
                        }
                        WorkMode.NO_ROOT -> Session.toast("当前运行于免 Root 共享 UID 模式")
                    }
                }
                .padding(horizontal = 20.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("工作模式"),
                    color = Rose.Text,
                    fontSize = fsp(15.5f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                val modeSubtitle = when (ui.workMode) {
                    WorkMode.ROOT -> if (ui.hasRootPermission) "Root · 已授权" else "Root · 未授权，点击检测"
                    WorkMode.NO_ROOT -> "免 Root · 共享 UID"
                }
                Text(
                    text = tr(modeSubtitle),
                    color = if (ui.workMode == WorkMode.ROOT && !ui.hasRootPermission) Rose.Red else Rose.TextSecond,
                    fontSize = fsp(12.5f)
                )
            }

            Spacer(Modifier.width(10.dp))

            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Rose.Surface.copy(alpha = if (isDark) 0.72f else 0.9f))
                        .border(0.8.dp, Rose.Divider, RoundedCornerShape(999.dp))
                        .clickableWithTooltip { modeDropdownOpen = true }
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(if (ui.workMode == WorkMode.ROOT) "Root" else "免 Root"),
                        color = Rose.Text,
                        fontSize = fsp(13.5f),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Rose.TextSecond,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = modeDropdownOpen,
                    onDismissRequest = { modeDropdownOpen = false },
                    modifier = Modifier.background(Rose.CardHigh)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tr("Root"),
                                color = if (ui.workMode == WorkMode.ROOT) Rose.Primary else Rose.Text,
                                fontSize = fsp(14f),
                                fontWeight = if (ui.workMode == WorkMode.ROOT) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        trailingIcon = if (ui.workMode == WorkMode.ROOT) {
                            { Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            modeDropdownOpen = false
                            Session.setWorkMode(WorkMode.ROOT)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tr("免 Root"),
                                color = if (ui.workMode == WorkMode.NO_ROOT) Rose.Primary else Rose.Text,
                                fontSize = fsp(14f),
                                fontWeight = if (ui.workMode == WorkMode.NO_ROOT) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        trailingIcon = if (ui.workMode == WorkMode.NO_ROOT) {
                            { Icon(Icons.Rounded.Check, null, tint = Rose.Primary, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            modeDropdownOpen = false
                            Session.setWorkMode(WorkMode.NO_ROOT)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeExtensionsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = tr("扩展"),
            color = Rose.Text,
            fontSize = fsp(24f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Rose.Card)
                    .border(0.8.dp, Rose.Divider, RoundedCornerShape(22.dp))
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Rose.Primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Extension,
                        contentDescription = tr("扩展"),
                        tint = Rose.Primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = tr("扩展插件"),
                    color = Rose.Text,
                    fontSize = fsp(17f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr("扩展插件系统将在后续版本中提供"),
                    color = Rose.TextSecond,
                    fontSize = fsp(13f)
                )
            }
        }
    }
}

/**
 * 设置页（清晰分层入口，不再直接堆叠外观与主题控件；所有子功能统一归位至此）
 */
@Composable
private fun HomeSettingsTab(
    ui: Ui,
    onOpenSubPage: (HomeSubPage) -> Unit
) {
    val currentLangCode = ui.settings["language"] ?: "0"
    val fontSummary = ui.customFontName.ifBlank { tr("系统默认") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("settings_title") {
            Text(
                text = tr("设置"),
                color = Rose.Text,
                fontSize = fsp(24f),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        item("general_section") { HomeSectionTitle("常规") }
        item("general_card") {
            HomeGroupedCard {
                HomeNavListRow(
                    icon = Icons.Rounded.Palette,
                    title = "外观与主题",
                    subtitle = "${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · $fontSummary"
                ) {
                    onOpenSubPage(HomeSubPage.APPEARANCE)
                }
                RowDivider()
                HomeNavListRow(
                    icon = Icons.Rounded.Language,
                    title = "界面语言",
                    subtitle = if (currentLangCode == "1" || currentLangCode == "2") "English" else "简体中文",
                    badgeText = "切换",
                    badgeColor = Rose.Primary
                ) {
                    Session.openSheet(Sheet.LANGUAGE)
                }
            }
        }

        item("support_section") { HomeSectionTitle("支持与诊断") }
        item("support_card") {
            HomeGroupedCard {
                HomeNavListRow(
                    icon = Icons.AutoMirrored.Rounded.HelpOutline,
                    title = "使用帮助",
                    subtitle = "搜索语法、联合搜索与功能说明"
                ) {
                    onOpenSubPage(HomeSubPage.HELP)
                }
                RowDivider()
                HomeNavListRow(
                    icon = Icons.Rounded.BugReport,
                    title = "运行日志",
                    subtitle = "查看引擎与附加记录"
                ) {
                    onOpenSubPage(HomeSubPage.LOG)
                }
                RowDivider()
                HomeNavListRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = "恢复默认设置",
                    subtitle = "重置主题、字体、透明度、屏幕占比与语言设置"
                ) {
                    Session.resetAppSettings()
                }
            }
        }

        item("about_section") { HomeSectionTitle("关于") }
        item("about_card") {
            HomeGroupedCard {
                HomeNavListRow(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "特别鸣谢",
                    subtitle = "致谢启发本项目设计的优秀工具"
                ) {
                    onOpenSubPage(HomeSubPage.THANKS)
                }
                RowDivider()
                HomeNavListRow(
                    icon = Icons.Rounded.Info,
                    title = "关于 GGNext",
                    subtitle = "版本 v0.6、官方群组与开源许可"
                ) {
                    onOpenSubPage(HomeSubPage.ABOUT)
                }
            }
        }
    }
}

// ============================================================
// 独立子页面顶部栏
// ============================================================
@Composable
private fun HomeSubPageTopBar(
    title: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = tr("返回"),
                tint = Rose.Text,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(20f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

// ============================================================
// 子页面 1：外观与主题（含完整多字体管理系统）
// ============================================================
@Composable
private fun HomeAppearanceSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val m = ui.metrics
    val ballSize = (ui.settings["icons_size"]?.toIntOrNull() ?: 48).coerceIn(28, 84)
    val bgOpacityPct = (m.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (m.panelScale * 100f).roundToInt().coerceIn(40, 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "外观与主题", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("theme_section") { HomeSectionTitle("色彩与深浅模式") }
            item("theme_card") {
                HomeGroupedCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(tr("主题配色"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val isDark = Rose.IsDark
                            ThemePreset.entries.chunked(4).forEach { rowPresets ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPresets.forEach { p ->
                                        val selected = ui.preset == p
                                        val swatch = p.seedFor(isDark)
                                        val checkTint = if (swatch.luminance() > 0.55f) Color(0xFF141013) else Color.White
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .clickableWithTooltip(tooltip = tr(p.label)) {
                                                    Session.setPreset(p)
                                                    Session.toast("${tr("已切换主题：")}${tr(p.label)}")
                                                },
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(swatch, CircleShape)
                                                    .then(
                                                        if (selected) Modifier.border(2.dp, Rose.Text, CircleShape)
                                                        else Modifier
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selected) {
                                                    Icon(Icons.Rounded.Check, null, tint = checkTint, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(3.dp))
                                            Text(
                                                tr(p.label),
                                                color = if (selected) Rose.Text else Rose.TextSecond,
                                                fontSize = fsp(10.5f),
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    RowDivider()

                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(tr("深色模式"), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkMode.entries.forEach { mode ->
                                val selected = ui.darkMode == mode
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .background(
                                            if (selected) Rose.ChipSelected else Rose.CardHigh,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .border(
                                            width = if (selected) 1.dp else 0.dp,
                                            color = if (selected) Rose.Primary else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickableWithTooltip {
                                            Session.setDarkMode(mode)
                                            Session.toast("${tr("深色模式")}：${tr(mode.label)}")
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        tr(mode.label),
                                        color = if (selected) Rose.Text else Rose.TextSecond,
                                        fontSize = fsp(13f),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    RowDivider()

                    SwitchRow("Android 12+ 动态壁纸取色", ui.dynamicColor) {
                        val next = !ui.dynamicColor
                        Session.setDynamicColor(next)
                        Session.toast(if (next) "已开启壁纸动态取色" else "已关闭壁纸动态取色")
                    }
                }
            }

            item("font_section") { HomeSectionTitle("字体管理") }
            item("font_manager_card") {
                HomeFontManagerCard(ui = ui)
            }

            item("layout_section") { HomeSectionTitle("悬浮窗与界面尺寸") }
            item("layout_card") {
                HomeGroupedCard {
                    SliderRow("背景透明度", bgOpacityPct.toFloat(), 25f..100f, "$bgOpacityPct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                        Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    SliderRow("页面屏幕占比", panelScalePct.toFloat(), 40f..100f, "$panelScalePct%") { pct ->
                        val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                        Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                    }
                    SliderRow("界面字体缩放", m.fontScale, 0.7f..1.3f, "%.2fx".format(m.fontScale)) {
                        Session.setSetting("font_scale", "%.2f".format(it))
                    }
                    SliderRow("弹窗圆角半径", m.dialogCorner.toFloat(), 0f..28f, "${m.dialogCorner}dp") {
                        Session.setSetting("dialog_corner", it.toInt().toString())
                    }
                    SliderRow("悬浮球尺寸", ballSize.toFloat(), 32f..76f, "${ballSize}dp") {
                        Session.setSetting("icons_size", it.toInt().toString())
                    }
                    RowDivider()
                    SwitchRow("列表入场与骨架屏动画", m.skeletonLoading) {
                        val next = !m.skeletonLoading
                        Session.setSetting("skeleton", if (next) "1" else "0")
                        Session.toast(if (next) "已启用列表动画" else "已关闭列表动画")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFontManagerCard(ui: Ui) {
    val activePath = ui.customFontPath
    val managedFonts = ui.managedFonts
    val hasActivityResultOwner = LocalActivityResultRegistryOwner.current != null

    HomeGroupedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.FontDownload,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr("已管理字体"),
                    color = Rose.Text,
                    fontSize = fsp(15f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tr("导入本地 .ttf / .otf 字体后可随时切换或删除，同步生效于主页与悬浮窗"),
                    color = Rose.TextDim,
                    fontSize = fsp(11.5f)
                )
            }
            Spacer(Modifier.width(10.dp))
            if (hasActivityResultOwner) {
                HomeSafFontPickerButton()
            } else {
                RoseButton(
                    label = "导入字体",
                    modifier = Modifier.height(34.dp),
                    filled = true
                ) {
                    Session.openFileBrowser(
                        initialPath = "/sdcard/Download",
                        selectDirectory = false,
                        returnSheet = Sheet.NONE,
                        target = FileBrowserTarget.FONT
                    )
                }
            }
        }

        RowDivider()

        // 1. 固定内置项：系统默认
        val isSystemSelected = activePath.isBlank()
        FontManagerRow(
            name = tr("系统默认"),
            subtitle = tr("不额外加载外部字体文件"),
            selected = isSystemSelected,
            canDelete = false,
            onSelect = { Session.selectFont("", "") },
            onDelete = {}
        )

        // 2. 用户导入的字体列表
        managedFonts.forEach { font ->
            RowDivider()
            val selected = activePath == font.path
            val sizeText = if (font.sizeBytes > 0L) AppFontCache.formatFontSize(font.sizeBytes) else ""
            val sub = if (sizeText.isNotEmpty()) "${tr("自定义字体")} · $sizeText" else tr("自定义字体")
            FontManagerRow(
                name = font.displayName,
                subtitle = sub,
                selected = selected,
                canDelete = true,
                onSelect = { Session.selectFont(font.path, font.displayName) },
                onDelete = { Session.deleteManagedFont(font.path) }
            )
        }
    }
}

@Composable
private fun FontManagerRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Rose.ChipSelected.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Rose.Primary else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (selected) Rose.Primary else Rose.TextDim,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) Rose.Primary else Rose.Text,
                fontSize = fsp(14.5f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                color = Rose.TextSecond,
                fontSize = fsp(11.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canDelete) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = tr("删除字体"),
                    tint = Rose.Red.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeSafFontPickerButton() {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            Session.importCustomFontFromUri(uri)
        }
    }
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Rose.Primary)
            .clickable {
                launcher.launch(
                    arrayOf(
                        "font/ttf",
                        "font/otf",
                        "application/x-font-ttf",
                        "application/x-font-opentype",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            text = tr("导入字体"),
            color = Color.White,
            fontSize = fsp(12.5f),
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// 子页面 2：使用帮助（独立界面）
// ============================================================
@Composable
private fun HomeHelpSubPage(onBack: () -> Unit) {
    val helpContent = remember(AppStrings.currentLang) {
        AppStrings.helpText(AppStrings.currentLang)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "使用帮助", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Rose.Card)
                    .border(0.8.dp, Rose.Divider, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Text(
                    text = helpContent,
                    color = Rose.TextSecond,
                    fontSize = fsp(13.5f),
                    lineHeight = fsp(22f)
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ============================================================
// 子页面 3：运行日志（独立界面）
// ============================================================
@Composable
private fun HomeLogSubPage(onBack: () -> Unit) {
    val clip = LocalClipboardManager.current
    var lines by remember { mutableStateOf(AppLog.snapshot()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(
            title = "运行日志",
            onBack = onBack,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (lines.isEmpty()) {
                                    Session.toast("暂无日志可复制")
                                } else {
                                    clip.setText(AnnotatedString(lines.joinToString("\n")))
                                    Session.toast("已复制运行日志")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ContentCopy, tr("复制"), tint = Rose.TextSecond, modifier = Modifier.size(19.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable {
                                AppLog.clear()
                                lines = emptyList()
                                Session.toast("日志已清空")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, tr("清空"), tint = Rose.Red.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Rose.Card)
                .border(0.8.dp, Rose.Divider, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = if (lines.isEmpty()) tr("暂无日志") else lines.joinToString("\n"),
                style = monoStyle(11, Rose.TextSecond),
                lineHeight = fsp(17.5f)
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ============================================================
// 子页面 4：特别鸣谢（独立界面，界面不展示任何 URL，仅点击跳转）
// ============================================================
@Composable
private fun HomeThanksSubPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    fun openExternalUrl(url: String) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        if (!opened) {
            clip.setText(AnnotatedString(url))
            Session.toast("无法打开浏览器，已复制链接")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "特别鸣谢", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("thanks_intro") {
                Text(
                    text = tr("GGNext 的设计与成长离不开以下优秀项目与先驱工具的启发，谨致以诚挚谢意。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(13f),
                    lineHeight = fsp(19.5f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            item("thanks_agg") {
                ThanksCreditCard(
                    title = "AGameGuardian",
                    tag = "界面与交互灵感",
                    description = "GGNext 的整体视觉层次、布局排布与多项交互细节，参考并致敬了 AGameGuardian 优秀的界面设计理念。",
                    onClick = { openExternalUrl("https://github.com/AGameGuardian/agameguardian.github.io") }
                )
            }

            item("thanks_gg") {
                ThanksCreditCard(
                    title = "GameGuardian",
                    tag = "经典内存分析工具先驱",
                    description = "致敬 Android 平台经典的内存分析与修改工具 GameGuardian。其开创性的搜索语法、联合搜索范式与内存浏览体系，为本项目奠定了重要参考与灵感。",
                    onClick = { openExternalUrl("https://gameguardian.net") }
                )
            }

            item("thanks_hint") {
                Text(
                    text = tr("点击卡片即可前往对应项目主页"),
                    color = Rose.TextDim,
                    fontSize = fsp(12f),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ThanksCreditCard(
    title: String,
    tag: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Rose.Card)
            .border(0.8.dp, Rose.Divider, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Rose.Primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = Rose.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Rose.Text,
                    fontSize = fsp(16f),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr(tag),
                    color = Rose.Primary,
                    fontSize = fsp(12f),
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = tr(description),
            color = Rose.TextSecond,
            fontSize = fsp(13f),
            lineHeight = fsp(19.5f)
        )
    }
}

// ============================================================
// 子页面 5：关于 GGNext（独立界面，非弹窗，含官方 QQ 群 1131034753）
// ============================================================
@Composable
private fun HomeAboutSubPage(
    ui: Ui,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val projectUrl = "https://github.com/2304974523/GGNext"
    val qqGroupNumber = "1131034753"

    fun openExternalUrl(url: String) {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        if (!opened) {
            clip.setText(AnnotatedString(url))
            Session.toast("无法打开浏览器，已复制链接")
        }
    }

    fun openOrCopyQqGroup() {
        clip.setText(AnnotatedString(qqGroupNumber))
        val qqUri = Uri.parse(
            "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$qqGroupNumber&card_type=group&source=qrcode"
        )
        val opened = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, qqUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        Session.toast(if (opened) "正在跳转 QQ 群：$qqGroupNumber" else "已复制 QQ 群号：$qqGroupNumber")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface)
            .navigationBarsPadding()
    ) {
        HomeSubPageTopBar(title = "关于 GGNext", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("about_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Rose.Card)
                        .border(0.8.dp, Rose.Divider, RoundedCornerShape(22.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Rose.Primary.copy(alpha = 0.16f))
                            .border(1.dp, Rose.Primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "G",
                            color = Rose.Primary,
                            fontSize = fsp(26f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "GGNext",
                        color = Rose.Text,
                        fontSize = fsp(20f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Rose.Primary.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "v0.6",
                            style = monoStyle(12, Rose.Primary, FontWeight.SemiBold)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = tr("开源 Android 内存分析与调试工具，采用 Jetpack Compose 与 C++20 原生引擎构建，遵循 GNU General Public License v3.0 协议发布。"),
                        color = Rose.TextSecond,
                        fontSize = fsp(12.5f),
                        lineHeight = fsp(19f)
                    )
                }
            }

            item("community_label") { HomeSectionTitle("社区与项目") }
            item("community_card") {
                HomeGroupedCard {
                    HomeNavListRow(
                        icon = Icons.Rounded.Groups,
                        title = "官方群组",
                        subtitle = "${tr("QQ 群")}：$qqGroupNumber · ${tr("交流反馈与更新动态")}",
                        badgeText = "加入",
                        badgeColor = Rose.Primary
                    ) {
                        openOrCopyQqGroup()
                    }
                    RowDivider()
                    HomeNavListRow(
                        icon = Icons.Rounded.Code,
                        title = "开源仓库",
                        subtitle = "GGNext GitHub · 查看源码、提交 Issue 与获取更新"
                    ) {
                        openExternalUrl(projectUrl)
                    }
                    RowDivider()
                    HomeInfoRow(
                        icon = Icons.Rounded.Info,
                        title = "开源协议",
                        value = "GNU GPL v3.0"
                    )
                }
            }

            item("runtime_label") { HomeSectionTitle("当前环境") }
            item("runtime_card") {
                HomeGroupedCard {
                    HomeInfoRow(
                        icon = Icons.Rounded.Build,
                        title = "工作模式",
                        value = tr(ui.workMode.title)
                    )
                    RowDivider()
                    HomeInfoRow(
                        icon = Icons.Rounded.Memory,
                        title = "当前目标",
                        value = ui.target?.label ?: tr("未选择")
                    )
                    RowDivider()
                    HomeInfoRow(
                        icon = Icons.Rounded.FontDownload,
                        title = "当前字体",
                        value = ui.customFontName.ifBlank { tr("系统默认") }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeBottomNavBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val items = listOf(
        Triple(HomeTab.HOME, Icons.Rounded.Home, "主页"),
        Triple(HomeTab.EXTENSIONS, Icons.Rounded.Extension, "扩展"),
        Triple(HomeTab.SETTINGS, Icons.Rounded.Settings, "设置")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Rose.Surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(Rose.Divider.copy(alpha = 0.65f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = selectedTab == tab
                var pressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(
                    if (pressed) 0.92f else 1f,
                    Motion.Fast,
                    label = "homeNavScale_${tab.name}"
                )
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 64.dp else 0.dp,
                    animationSpec = tween(220),
                    label = "homeNavPill_${tab.name}"
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) primary else Rose.TextSecond,
                    animationSpec = Motion.ColorNormal,
                    label = "homeNavTint_${tab.name}"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale)
                        .clickableWithTooltip(
                            tooltip = label,
                            onPressChange = { pressed = it },
                            onClick = { onSelect(tab) }
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pillWidth > 1.dp) {
                            Box(
                                modifier = Modifier
                                    .width(pillWidth)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(primary.copy(alpha = if (Rose.IsDark) 0.22f else 0.16f))
                            )
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = tr(label),
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    AnimatedVisibility(visible = selected) {
                        Text(
                            text = tr(label),
                            color = Rose.Text,
                            fontSize = fsp(11.5f),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(text: String) {
    Text(
        text = tr(text),
        color = Rose.Primary,
        fontSize = fsp(13.5f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun HomeGroupedCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Rose.Card)
            .border(0.8.dp, Rose.Divider, RoundedCornerShape(20.dp))
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun HomeNavListRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = Rose.Primary,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (pressed) Rose.CardHigh else Color.Transparent,
        Motion.ColorFast,
        label = "homeRowBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Rose.TextSecond,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(15.5f),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr(subtitle),
                color = Rose.TextSecond,
                fontSize = fsp(12.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (badgeText != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tr(badgeText),
                    color = badgeColor,
                    fontSize = fsp(11.5f),
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HomeInfoRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Rose.TextSecond,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(14.5f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            color = Rose.TextSecond,
            fontSize = fsp(13f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
