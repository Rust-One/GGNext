package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.core.WorkMode
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.ui.components.GgSwitch
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.WavySlider
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.dialogEnter
import com.littlecodesauce.ggnext.ui.components.dialogExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlin.math.roundToInt

/**
 * 悬浮窗内部的设置页：
 * 1. 通用设置（背景透明度、工作模式、页面屏幕占比、工具栏顺序与增删、外观与字体缩放、语言）
 * 2. 内存设置（进程选择、内存区域、自动暂停游戏、冻结间隔、搜索结果过滤器、数值显示格式、变速倍率设置）
 * 3. 操作与诊断（清除输入历史、运行日志、帮助文档、关于、终止此进程、退出）
 */
@Composable
fun SettingsScreen(ui: Ui, embedded: Boolean = false) {
    val autopause = (ui.settings["autopause"] ?: "0") == "1"
    val rawFreeze = ui.settings["freeze_interval"]?.toLongOrNull() ?: 33L
    val freezeIntervalMs = (if (rawFreeze > 1000L) rawFreeze / 1000L else rawFreeze).coerceIn(5L, 500L)

    val bgOpacityPct = (ui.metrics.bgOpacity * 100f).roundToInt().coerceIn(25, 100)
    val panelScalePct = (ui.metrics.panelScale * 100f).roundToInt().coerceIn(40, 100)

    var confirmKillProcess by remember { mutableStateOf(false) }
    var confirmExitApp by remember { mutableStateOf(false) }
    val listState = rememberPersistentLazyListState(if (embedded) "home_settings_list" else "settings_list")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. 通用设置
            item("general_section") { SectionLabel("通用设置") }
            item("general_card") {
                SettingsCard {
                    SliderRow(
                        title = "背景透明度",
                        value = bgOpacityPct.toFloat(),
                        range = 25f..100f,
                        display = "$bgOpacityPct%"
                    ) { pct ->
                        val ratio = (pct.roundToInt().coerceIn(25, 100)) / 100f
                        Session.setSetting("bg_opacity", String.format(java.util.Locale.US, "%.2f", ratio))
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.Security,
                        title = "工作模式",
                        subtitle = when (ui.workMode) {
                            WorkMode.ROOT -> if (ui.hasRootPermission) "Root 模式 · 已授权" else "Root 模式 · 未授权，点击切换"
                            WorkMode.NO_ROOT -> "免 Root 模式 · 共享 UID"
                        }
                    ) {
                        val nextMode = when (ui.workMode) {
                            WorkMode.ROOT -> WorkMode.NO_ROOT
                            WorkMode.NO_ROOT -> WorkMode.ROOT
                        }
                        Session.setWorkMode(nextMode)
                    }

                    RowDivider()

                    SliderRow(
                        title = "页面屏幕占比",
                        value = panelScalePct.toFloat(),
                        range = 40f..100f,
                        display = "$panelScalePct%"
                    ) { pct ->
                        val ratio = (pct.roundToInt().coerceIn(40, 100)) / 100f
                        Session.setSetting("panel_scale", String.format(java.util.Locale.US, "%.2f", ratio))
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.Layers,
                        title = "工具栏顺序与增删",
                        subtitle = "调整搜索列表 / 保存列表 / 内存页工具栏图标"
                    ) {
                        Session.openSheet(Sheet.TOOLBAR_ORDER)
                    }

                    RowDivider()

                    val fontSub = ui.customFontName.ifBlank { tr("系统默认") }
                    NavRow(
                        icon = Icons.Rounded.Palette,
                        title = "外观与主题",
                        subtitle = "当前主题：${tr(ui.preset.label)} · ${tr(ui.darkMode.label)} · $fontSub"
                    ) {
                        Session.openSheet(Sheet.APPEARANCE)
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.Language,
                        title = "语言",
                        subtitle = when (ui.settings["language"]) {
                            "1", "2" -> "English"
                            else -> "简体中文"
                        }
                    ) {
                        Session.openSheet(Sheet.LANGUAGE)
                    }
                }
            }

            // 2. 内存设置
            item("memory_section") { SectionLabel("内存设置") }
            item("memory_card") {
                SettingsCard {
                    NavRow(
                        icon = Icons.Rounded.Memory,
                        title = "进程选择",
                        subtitle = ui.target?.let { "[${it.pid}] ${it.label} · ${if (it.x64) "x64" else "x86"}" } ?: "未选择进程，点击选择"
                    ) {
                        Session.openSheet(Sheet.PROCESS)
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.Layers,
                        title = "内存区域",
                        subtitle = ui.selectedRegions.joinToString(", ").ifEmpty { "未选择" }
                    ) {
                        Session.openSheet(Sheet.REGION)
                    }

                    RowDivider()

                    SwitchRow("自动暂停游戏", autopause) {
                        val next = if (autopause) "0" else "1"
                        Session.setSetting("autopause", next)
                        Session.toast(if (next == "1") "已开启自动暂停游戏" else "已关闭自动暂停游戏")
                    }

                    RowDivider()

                    SliderRow(
                        title = "冻结间隔",
                        value = freezeIntervalMs.toFloat(),
                        range = 5f..500f,
                        display = "$freezeIntervalMs ms"
                    ) {
                        Session.setSetting("freeze_interval", it.roundToInt().coerceIn(5, 500).toString())
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.FilterAlt,
                        title = "搜索结果过滤器",
                        subtitle = "最大显示 ${ui.filter.maxShow} 条 · 地址/数值/类型筛选"
                    ) {
                        Session.openSheet(Sheet.FILTER)
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "数值显示格式",
                        subtitle = "当前：${Formatter.maskSummary(ui.valueMask)}"
                    ) {
                        Session.openSheet(Sheet.VALUE_FORMAT)
                    }

                    RowDivider()

                    NavRow(
                        icon = Icons.Rounded.Speed,
                        title = "变速倍率设置",
                        subtitle = "当前：${ui.speedText}"
                    ) {
                        Session.openSheet(Sheet.SPEED)
                    }
                }
            }

            // 3. 操作与诊断
            item("op_section") { SectionLabel("操作与诊断") }
            item("op_card") {
                SettingsCard {
                    NavRow(
                        icon = Icons.Rounded.DeleteSweep,
                        title = "清除输入历史",
                        subtitle = "当前共有 ${ui.history.size} 条输入记录"
                    ) {
                        Session.clearHistory()
                    }
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.BugReport,
                        title = "运行日志",
                        subtitle = "查看引擎与附加记录"
                    ) {
                        Session.openSheet(Sheet.LOG)
                    }
                    RowDivider()
                    NavRow(
                        icon = Icons.AutoMirrored.Rounded.HelpOutline,
                        title = "帮助文档",
                        subtitle = "查看搜索语法与功能说明"
                    ) {
                        Session.openSheet(Sheet.HELP)
                    }
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.Info,
                        title = "关于",
                        subtitle = "GGNext v0.6"
                    ) {
                        Session.openSheet(Sheet.ABOUT)
                    }
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.Close,
                        title = "终止此进程",
                        subtitle = ui.target?.let { "终止当前选中的进程 [${it.pid}] ${it.label}" } ?: "当前未附加目标进程"
                    ) {
                        if (ui.target == null) {
                            Session.toast("当前未附加目标进程")
                        } else {
                            confirmKillProcess = true
                        }
                    }
                    RowDivider()
                    NavRow(
                        icon = Icons.Rounded.Close,
                        title = "退出",
                        subtitle = "关闭悬浮窗并完全退出修改器"
                    ) {
                        confirmExitApp = true
                    }
                }
            }
        }

        // 二次确认弹窗：终止此进程 / 退出软件
        val showConfirm = confirmKillProcess || confirmExitApp
        AnimatedVisibility(
            visible = showConfirm,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().zIndex(20f)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Rose.Scrim)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        confirmKillProcess = false
                        confirmExitApp = false
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showConfirm,
                    enter = dialogEnter(),
                    exit = dialogExit()
                ) {
                    val isKill = confirmKillProcess
                    val target = ui.target
                    val titleText = if (isKill) "确认终止进程" else "确认退出"
                    val bodyText = if (isKill) {
                        if (target != null) {
                            "确定要强制终止目标进程 [${target.pid}] ${target.label} 吗？进程未保存的数据可能会丢失。"
                        } else {
                            "当前未选择目标进程。"
                        }
                    } else {
                        "确定要关闭悬浮窗并完全退出 GGNext 吗？"
                    }
                    val confirmBtnLabel = if (isKill) "确认终止" else "确认退出"

                    Column(
                        Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {}
                            .background(Rose.Card, RoundedCornerShape(16.dp))
                            .border(1.dp, Rose.Divider, RoundedCornerShape(16.dp))
                            .padding(18.dp)
                    ) {
                        Text(
                            text = tr(titleText),
                            color = Rose.Text,
                            fontSize = fsp(17f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = tr(bodyText),
                            color = Rose.TextSecond,
                            fontSize = fsp(13.5f),
                            lineHeight = fsp(19.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RoseButton(
                                label = "取消",
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                confirmKillProcess = false
                                confirmExitApp = false
                            }
                            RoseButton(
                                label = confirmBtnLabel,
                                filled = true,
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                val doKill = confirmKillProcess
                                confirmKillProcess = false
                                confirmExitApp = false
                                if (doKill) {
                                    Session.terminateTargetProcess()
                                } else {
                                    Session.exitApp()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        tr(text),
        color = Rose.Primary,
        fontSize = fsp(13f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Rose.Card.copy(alpha = (bgOpacity * 0.90f).coerceIn(0.20f, 1.0f)), RoundedCornerShape(16.dp))
            .border(1.dp, Rose.Divider, RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp)
    ) { content() }
}

@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp)
            .height(1.dp)
            .background(Rose.Divider)
    )
}

@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val bg by animateColorAsState(
        if (pressed) Rose.CardHigh.copy(alpha = bgOpacity) else Color.Transparent,
        Motion.ColorFast,
        label = "navRowBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(Rose.CardHigh.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f)), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Medium)
            Text(tr(subtitle), color = Rose.TextSecond, fontSize = fsp(12f))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Rose.TextDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableWithTooltip(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr(title), color = Rose.Text, fontSize = fsp(15f), modifier = Modifier.weight(1f))
        GgSwitch(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

/** 统一纤细波浪滑条：常态细线显示，拖动时变为动态波浪线 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit
) {
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(14.5f), modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .background(Rose.CardHigh.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f)), RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) { Text(display, color = Rose.TextSecond, fontSize = fsp(12f)) }
        }
        Spacer(Modifier.height(2.dp))
        WavySlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            activeColor = Rose.Primary,
            inactiveColor = Rose.CardHigh.copy(alpha = bgOpacity)
        )
    }
}
