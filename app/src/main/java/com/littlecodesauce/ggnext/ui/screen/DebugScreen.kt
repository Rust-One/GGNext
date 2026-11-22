package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.model.BpCallFrame
import com.littlecodesauce.ggnext.model.BpFpRegEntry
import com.littlecodesauce.ggnext.model.BpHitGroup
import com.littlecodesauce.ggnext.model.BpRegEntry
import com.littlecodesauce.ggnext.model.BpSession
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.Hairline
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.expandEnter
import com.littlecodesauce.ggnext.ui.components.expandExit
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 硬件断点调试器主界面（NextTab.DEBUG）：
 * - 顶部紧凑工具栏（新增断点、暂停/恢复全部、清空命中、删除、全选、反选、取消选择、活动数量徽章）
 * - 折叠式断点会话卡片，嵌套按 PC 聚合的命中指令列表
 * - 展开命中项内直接内联展示寄存器面板（[ X | 浮点 ] 双栏 Hex）与调用链回溯（点击跳转内存）
 */
@Composable
fun DebugScreen(ui: Ui) {
    LaunchedEffect(Unit) {
        Session.refreshBreakpointsNow()
    }

    val expandedBps = remember { mutableStateMapOf<Int, Boolean>() }
    val expandedHits = remember { mutableStateMapOf<String, Boolean>() }
    val activeCount = remember(ui.breakpoints) { ui.breakpoints.count { it.isActive } }
    val anyActive = activeCount > 0

    Column(Modifier.fillMaxSize()) {
        DebugActionBar(
            activeCount = activeCount,
            anyActive = anyActive,
            hasBreakpoints = ui.breakpoints.isNotEmpty(),
            hasSelection = ui.selectedBpIds.isNotEmpty(),
            onAdd = { Session.openSheet(Sheet.BREAKPOINT) },
            onTogglePauseAll = { Session.toggleAllBreakpointsPause() },
            onClearHits = { Session.clearBreakpointHits(0) },
            onDeleteSelected = { Session.removeSelectedBreakpoints() },
            onSelectAll = { Session.selectAllBreakpoints() },
            onInvert = { Session.invertBpSelection() },
            onClearSelection = { Session.clearBpSelection() }
        )
        Hairline()

        if (ui.breakpoints.isEmpty()) {
            DebugEmptyState(
                attached = ui.attachedOk,
                onAdd = { Session.openSheet(Sheet.BREAKPOINT) }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 52.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = ui.breakpoints,
                    key = { it.id }
                ) { bp ->
                    val bpExpanded = expandedBps[bp.id] ?: (bp.hits.isNotEmpty())
                    BreakpointSessionCard(
                        bp = bp,
                        selected = bp.id in ui.selectedBpIds,
                        expanded = bpExpanded,
                        expandedHits = expandedHits,
                        onToggleSelect = { Session.toggleBpSelection(bp.id) },
                        onToggleExpand = { expandedBps[bp.id] = !bpExpanded },
                        onTogglePause = { Session.toggleBreakpointPause(bp.id) },
                        onDelete = { Session.removeBreakpoint(bp.id) },
                        onToggleHit = { key ->
                            expandedHits[key] = !(expandedHits[key] ?: false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugActionBar(
    activeCount: Int,
    anyActive: Boolean,
    hasBreakpoints: Boolean,
    hasSelection: Boolean,
    onAdd: () -> Unit,
    onTogglePauseAll: () -> Unit,
    onClearHits: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClearSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DebugBarIconButton(
            icon = Icons.Rounded.Add,
            tooltip = "添加断点",
            tint = Rose.Primary,
            enabled = true,
            onClick = onAdd
        )
        DebugBarIconButton(
            icon = if (anyActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            tooltip = if (anyActive) "暂停全部断点" else "恢复全部断点",
            tint = if (hasBreakpoints) Rose.Text else Rose.TextDim,
            enabled = hasBreakpoints,
            onClick = onTogglePauseAll
        )
        DebugBarIconButton(
            icon = Icons.Rounded.CleaningServices,
            tooltip = "清空命中记录",
            tint = if (hasBreakpoints) Rose.Text else Rose.TextDim,
            enabled = hasBreakpoints,
            onClick = onClearHits
        )
        DebugBarIconButton(
            icon = Icons.Rounded.DeleteOutline,
            tooltip = if (hasSelection) "删除选中断点" else "删除全部断点",
            tint = if (hasBreakpoints) Rose.Red else Rose.TextDim,
            enabled = hasBreakpoints,
            onClick = onDeleteSelected
        )
        DebugBarIconButton(
            icon = Icons.Rounded.DoneAll,
            tooltip = "全选断点",
            tint = if (hasBreakpoints) Rose.Text else Rose.TextDim,
            enabled = hasBreakpoints,
            onClick = onSelectAll
        )
        DebugBarIconButton(
            icon = Icons.Rounded.SwapHoriz,
            tooltip = "反选断点",
            tint = if (hasBreakpoints) Rose.Text else Rose.TextDim,
            enabled = hasBreakpoints,
            onClick = onInvert
        )
        DebugBarIconButton(
            icon = Icons.Rounded.Close,
            tooltip = "取消选择",
            tint = if (hasSelection) Rose.Text else Rose.TextDim,
            enabled = hasSelection,
            onClick = onClearSelection
        )

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (activeCount > 0) Rose.Primary.copy(alpha = if (Rose.IsDark) 0.22f else 0.16f)
                    else Rose.CardHigh
                )
                .border(
                    width = 0.8.dp,
                    color = if (activeCount > 0) Rose.Primary.copy(alpha = 0.45f) else Rose.Divider,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${tr("活动")} $activeCount",
                color = if (activeCount > 0) Rose.Primary else Rose.TextSecond,
                fontSize = fsp(12f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DebugBarIconButton(
    icon: ImageVector,
    tooltip: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Rose.CardHigh.copy(alpha = if (enabled) 0.72f else 0.35f))
            .clickableWithTooltip(tooltip = tr(tooltip), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tr(tooltip),
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun BreakpointSessionCard(
    bp: BpSession,
    selected: Boolean,
    expanded: Boolean,
    expandedHits: Map<String, Boolean>,
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onTogglePause: () -> Unit,
    onDelete: () -> Unit,
    onToggleHit: (String) -> Unit
) {
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val typeAccent = bpTypeColor(bp.type)
    val statusText = when {
        bp.isActive -> tr("运行中")
        bp.paused && bp.running -> tr("已暂停")
        else -> tr("已停止")
    }
    val statusColor = when {
        bp.isActive -> Rose.Primary
        bp.paused && bp.running -> Rose.Yellow
        else -> Rose.TextDim
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Rose.Card.copy(alpha = (bgOpacity * 0.94f).coerceIn(0.25f, 1.0f)))
            .border(
                width = if (selected) 1.2.dp else 0.8.dp,
                color = if (selected) Rose.Primary else Rose.Divider,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GgCheckBox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                size = 19.dp,
                accentColor = Rose.Primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "#${bp.id}",
                style = monoStyle(12, Rose.TextDim, weight = FontWeight.Medium)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = bp.addressHex,
                style = monoStyle(14, Rose.Text, weight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = tr(bp.type.shortLabel),
                color = typeAccent,
                fontSize = fsp(12.5f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${bp.size}B",
                style = monoStyle(10.5f.toInt(), Rose.TextDim)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickableWithTooltip(tooltip = if (bp.isActive) tr("暂停") else tr("恢复")) {
                        onTogglePause()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (bp.isActive) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = if (bp.isActive) Rose.Text else Rose.Green,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickableWithTooltip(tooltip = tr("删除断点")) {
                        onDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = Rose.Red.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        val tidPreview = if (bp.sampleTids.isNotEmpty()) {
            "  TID ${bp.sampleTids.take(2).joinToString(",")}${if (bp.sampleTids.size > 2) "…" else ""}"
        } else ""
        val backendTag = if (bp.tracerPid > 0) "${bp.mode.badge} ${bp.tracerPid}" else bp.mode.badge

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 27.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText,
                color = statusColor,
                fontSize = fsp(11.5f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "  ${tr("命中")} ${bp.totalHits}  ${tr("目标")} ${bp.pid}  $backendTag  ${tr("线程")} ${bp.attachedThreads}/${bp.totalThreads}$tidPreview",
                style = monoStyle(11, Rose.TextSecond),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (bp.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Red.copy(alpha = 0.14f))
                    .border(0.8.dp, Rose.Red.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = bp.lastError,
                    color = Rose.Red,
                    fontSize = fsp(11f),
                    lineHeight = fsp(15f)
                )
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (bp.hits.isEmpty()) {
                    Text(
                        text = tr("正在监听中，等待目标进程访问此地址…"),
                        color = Rose.TextDim,
                        fontSize = fsp(11.5f),
                        modifier = Modifier.padding(start = 27.dp, top = 2.dp, bottom = 4.dp)
                    )
                } else {
                    bp.hits.forEachIndexed { idx, hit ->
                        val hitKey = "${bp.id}_${hit.pc}_${hit.hitType.code}"
                        val isHitOpen = expandedHits[hitKey] ?: (idx == 0 && bp.hits.size == 1)
                        BpHitGroupRow(
                            pid = bp.pid,
                            hitIndex = idx + 1,
                            hit = hit,
                            expanded = isHitOpen,
                            onToggle = { onToggleHit(hitKey) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BpHitGroupRow(
    pid: Int,
    hitIndex: Int,
    hit: BpHitGroup,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var regTab by remember(hit.pc) { mutableIntStateOf(0) } // 0 = X (GPR), 1 = 浮点 (FP/SIMD)
    val hitTypeColor = bpTypeColor(hit.hitType)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Rose.Row.copy(alpha = 0.92f))
            .border(0.7.dp, Rose.Divider.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // 命中汇总行：只读/只写 PC <hex> <module> 命中 <N> ▾
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(hit.hitType.shortLabel),
                color = hitTypeColor,
                fontSize = fsp(12f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "PC ${hit.pcHex}",
                style = monoStyle(12.5f.toInt(), Rose.Text, weight = FontWeight.Medium)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = hit.moduleName,
                style = monoStyle(11.5f.toInt(), Rose.Primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${tr("命中")} ${hit.count}",
                style = monoStyle(11, Rose.TextSecond)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Rose.TextSecond,
                modifier = Modifier.size(17.dp)
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandEnter(), exit = expandExit()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 指令反汇编与快捷跳转条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Rose.CardHigh.copy(alpha = 0.85f))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${tr("命中")} #$hitIndex",
                        style = monoStyle(11, Rose.TextSecond)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "PC ${hit.pcHex}",
                            style = monoStyle(11.5f.toInt(), Rose.Text)
                        )
                        val disasmLabel = hit.disasmText.ifBlank {
                            if (hit.insnHex.isNotEmpty()) "RAW ${hit.insnHex}" else "--"
                        }
                        Text(
                            text = disasmLabel,
                            style = monoStyle(12.5f.toInt(), Rose.Primary, weight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickableWithTooltip(tooltip = tr("复制指令信息")) {
                                val text = "PC 0x${hit.pcHex} (${hit.moduleName}+0x${java.lang.Long.toUnsignedString(hit.moduleOffset, 16).uppercase()}) ${hit.disasmText}"
                                clipboard.setText(AnnotatedString(text))
                                Session.toast("已复制命中指令")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = Rose.TextSecond, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickableWithTooltip(tooltip = tr("转到 PC 地址")) {
                                Session.goto(hit.pc, recordHistory = true, highlightTarget = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.OpenInNew, null, tint = Rose.Primary, modifier = Modifier.size(16.dp))
                    }
                }

                // 元数据行：目标 · TID · 架构 · PC · SP · LR
                val metaLine = buildString {
                    append(tr("目标")).append(' ').append(pid)
                    append("   TID ").append(hit.tid)
                    append("   ").append(tr("架构")).append(' ').append(hit.arch)
                    append("\nPC ").append(hit.pcHex)
                    if (hit.sp != 0L) append("   SP ").append(hit.spHex)
                    if (hit.lr != 0L) append("   LR ").append(hit.lrHex)
                }
                Text(
                    text = metaLine,
                    style = monoStyle(11, Rose.TextSecond),
                    lineHeight = fsp(16f)
                )

                // 寄存器面板（自带 X / 浮点 切换）
                if (hit.gprs.isNotEmpty() || hit.fprs.isNotEmpty()) {
                    Text(
                        text = tr("寄存器"),
                        color = Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RegTabPill(label = "X", selected = regTab == 0) { regTab = 0 }
                        RegTabPill(
                            label = if (hit.fprs.isNotEmpty()) tr("浮点") else "${tr("浮点")} (${hit.fprs.size})",
                            selected = regTab == 1
                        ) { regTab = 1 }
                    }
                    if (regTab == 0) {
                        RegisterGprGrid(
                            regs = hit.gprs,
                            onTapReg = { reg ->
                                if (reg.value != 0L) {
                                    clipboard.setText(AnnotatedString(reg.hex))
                                    Session.toast("已复制 ${reg.name} = ${reg.hex}")
                                }
                            },
                            onJumpReg = { reg ->
                                if (java.lang.Long.compareUnsigned(reg.value, 0x10000L) >= 0) {
                                    Session.goto(reg.value, recordHistory = true, highlightTarget = true)
                                }
                            }
                        )
                    } else {
                        RegisterFprList(
                            fprs = hit.fprs,
                            onTapFpr = { fp ->
                                clipboard.setText(AnnotatedString(fp.hexValue))
                                Session.toast("已复制 ${fp.name} = ${fp.hexValue}")
                            }
                        )
                    }
                }

                // 回溯调用链（自带在命中结果里，点击可直接跳入内存编辑器）
                if (hit.callstack.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${tr("回溯")} (${hit.callstack.size})",
                        color = Rose.TextSecond,
                        fontSize = fsp(11.5f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        hit.callstack.forEachIndexed { fIdx, frame ->
                            CallstackFrameRow(
                                index = fIdx + 1,
                                frame = frame,
                                onJump = {
                                    Session.goto(frame.address, recordHistory = true, highlightTarget = true)
                                },
                                onCopy = {
                                    val txt = "${frame.addressHex} (${frame.moduleName}+${frame.offsetHex})"
                                    clipboard.setText(AnnotatedString(txt))
                                    Session.toast("已复制回溯帧 #${fIdx + 1}")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) Rose.Primary.copy(alpha = if (Rose.IsDark) 0.30f else 0.20f)
                else Rose.CardHigh
            )
            .border(
                width = 0.8.dp,
                color = if (selected) Rose.Primary else Rose.Divider,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Rose.Primary else Rose.TextSecond,
            fontSize = fsp(11.5f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun RegisterGprGrid(
    regs: List<BpRegEntry>,
    onTapReg: (BpRegEntry) -> Unit,
    onJumpReg: (BpRegEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        regs.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { reg ->
                    val valColor = registerValueColor(reg)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = { onTapReg(reg) })
                            .padding(horizontal = 4.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reg.name,
                            style = monoStyle(11, Rose.Primary.copy(alpha = 0.85f), weight = FontWeight.Medium),
                            modifier = Modifier.width(34.dp)
                        )
                        Text(
                            text = reg.hex,
                            style = monoStyle(11, valColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    enabled = java.lang.Long.compareUnsigned(reg.value, 0x10000L) >= 0,
                                    onClick = { onJumpReg(reg) }
                                )
                        )
                    }
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RegisterFprList(
    fprs: List<BpFpRegEntry>,
    onTapFpr: (BpFpRegEntry) -> Unit
) {
    if (fprs.isEmpty()) {
        Text(
            text = tr("当前断点模式未采集浮点/向量寄存器（切换到 Ptrace 模式可采集完整 V0–V31 寄存器）"),
            color = Rose.TextDim,
            fontSize = fsp(11f),
            lineHeight = fsp(16f)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        fprs.forEach { fp ->
            val isZero = fp.hexValue.all { it == '0' }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onTapFpr(fp) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = fp.name,
                    style = monoStyle(11, Rose.Primary.copy(alpha = 0.85f), weight = FontWeight.Medium),
                    modifier = Modifier.width(34.dp)
                )
                Text(
                    text = fp.hexValue,
                    style = monoStyle(10.5f.toInt(), if (isZero) Rose.TextDim else Rose.Cyan),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CallstackFrameRow(
    index: Int,
    frame: BpCallFrame,
    onJump: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Rose.CardHigh.copy(alpha = 0.72f))
            .clickable(onClick = onJump)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$index",
            style = monoStyle(11, Rose.TextDim),
            modifier = Modifier.width(26.dp)
        )
        Text(
            text = frame.addressHex,
            style = monoStyle(11.5f.toInt(), Rose.PointerX, weight = FontWeight.Medium)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (frame.moduleName != "[anon]") "${frame.moduleName}+${frame.offsetHex}" else "[anon]",
            style = monoStyle(11, Rose.TextSecond),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCopy),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.ContentCopy, null, tint = Rose.TextDim, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun DebugEmptyState(
    attached: Boolean,
    onAdd: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Rose.Primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = tr("硬件断点调试"),
                    tint = Rose.Primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = tr("硬件断点调试器"),
                color = Rose.Text,
                fontSize = fsp(15.5f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = tr(
                    if (attached) "支持 Perf (Root) 与 Ptrace (支持免 Root) 两种断点模式，命中后自动采集寄存器与调用链回溯"
                    else "请先附加目标进程，然后从内存/搜索列表长按地址或点击上方 + 添加硬件断点"
                ),
                color = Rose.TextSecond,
                fontSize = fsp(12f),
                lineHeight = fsp(18f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            RoseButton(
                label = "添加硬件断点",
                filled = true,
                enabled = attached,
                modifier = Modifier.height(38.dp)
            ) {
                onAdd()
            }
        }
    }
}

private fun bpTypeColor(type: BpType): Color = when (type) {
    BpType.READ -> Rose.Yellow
    BpType.WRITE -> Rose.Red
    BpType.RW -> Rose.Violet
    BpType.EXEC -> Rose.Cyan
}

private fun registerValueColor(reg: BpRegEntry): Color {
    val n = reg.name.lowercase()
    if (reg.value == 0L) return Rose.TextDim
    if (n == "pc" || n == "lr" || n == "rip" || n == "eip") return Rose.PointerX
    if (n == "sp" || n == "fp" || n == "rsp" || n == "rbp" || n == "esp" || n == "ebp") return Rose.PointerW
    val u = reg.value
    return if (java.lang.Long.compareUnsigned(u, 0x10000000L) >= 0 &&
        java.lang.Long.compareUnsigned(u, 0x0000FFFFFFFFFFFFL) <= 0
    ) {
        Rose.PointerW
    } else {
        Rose.Text
    }
}
