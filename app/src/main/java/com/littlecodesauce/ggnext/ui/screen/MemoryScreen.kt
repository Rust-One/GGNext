package com.littlecodesauce.ggnext.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Sheet
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.ui.components.GgCheckBox
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableLongPress
import com.littlecodesauce.ggnext.ui.components.clickableWithTooltip
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.components.rememberPersistentLazyListState
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.components.valueText
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose
import com.littlecodesauce.ggnext.ui.theme.pointerColor
import com.littlecodesauce.ggnext.ui.theme.pointerTypeLabel
import com.littlecodesauce.ggnext.ui.theme.typeColor
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(ui: Ui) {
    val listState = rememberPersistentLazyListState("memory_list")
    val scope = rememberCoroutineScope()
    var lastHandledMemoryScrollTrigger by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(-1)
    }

    val firstAddr = ui.memoryRows.firstOrNull()?.address ?: 0L
    val lastAddr = ui.memoryRows.lastOrNull()?.address ?: 0L
    val hasTopBoundary = ui.memoryRows.isNotEmpty() && java.lang.Long.compareUnsigned(firstAddr, 4L) >= 0
    val topJumpOffset = if (hasTopBoundary) 1 else 0

    LaunchedEffect(ui.memoryScrollTrigger, ui.memoryHighlightAddress, ui.memoryRows.size) {
        if (ui.memoryScrollTrigger != lastHandledMemoryScrollTrigger && ui.memoryRows.isNotEmpty()) {
            lastHandledMemoryScrollTrigger = ui.memoryScrollTrigger
            val targetAddr = ui.memoryHighlightAddress
            if (targetAddr != null) {
                val idx = ui.memoryRows.indexOfFirst { it.address == targetAddr }
                if (idx >= 0) {
                    val listIdx = idx + topJumpOffset
                    val scrollIdx = when {
                        idx == 0 -> listIdx
                        idx >= ui.memoryRows.lastIndex - 1 -> (listIdx - 4).coerceAtLeast(0)
                        else -> (listIdx - 2).coerceAtLeast(0)
                    }
                    listState.scrollToItem(scrollIdx)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MemoryMiniBar(
            ui = ui,
            onLocateAddress = {
                val targetAddr = ui.memoryHighlightAddress ?: ui.memoryBase
                val idx = ui.memoryRows.indexOfFirst { it.address == targetAddr }
                if (idx >= 0) {
                    val listIdx = idx + topJumpOffset
                    scope.launch {
                        listState.animateScrollToItem((listIdx - 2).coerceAtLeast(0))
                    }
                    Session.toast("已定位到地址 0x${java.lang.Long.toHexString(targetAddr).uppercase()}")
                } else {
                    Session.focusHighlightedMemoryAddress()
                }
            }
        )
        if (ui.memoryRows.isEmpty()) {
            EmptyMemory()
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 2.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (hasTopBoundary) {
                        val prevAddr = (firstAddr - 4L) and -4L
                        item(key = "mem_boundary_top", contentType = "mem_boundary") {
                            MemoryBoundaryJumpButton(
                                targetAddress = prevAddr,
                                isUpward = true
                            )
                        }
                    }
                    itemsIndexed(
                        items = ui.memoryRows,
                        key = { _, i -> i.address },
                        contentType = { _, _ -> "mem_row" }
                    ) { _, item ->
                        MemoryRow(
                            item = item,
                            checked = ui.isMemoryRowSelected(item),
                            mask = ui.valueMask,
                            disasmText = ui.memoryDisasm[item.address],
                            isJumpTarget = (item.address == ui.memoryHighlightAddress)
                        )
                    }
                    val nextAddr = (lastAddr + 4L) and -4L
                    item(key = "mem_boundary_bottom", contentType = "mem_boundary") {
                        MemoryBoundaryJumpButton(
                            targetAddress = nextAddr,
                            isUpward = false
                        )
                    }
                }
                VerticalFastScrollbar(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

@Composable
private fun MemoryBoundaryJumpButton(
    targetAddress: Long,
    isUpward: Boolean
) {
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val addrHex = remember(targetAddress) {
        "0x" + java.lang.Long.toHexString(targetAddress).uppercase()
    }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.Fast, label = "memBoundaryScale")

    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .background(
                Rose.CardHigh.copy(alpha = (bgOpacity * 0.95f).coerceIn(0.25f, 1.0f)),
                RoundedCornerShape(8.dp)
            )
            .border(1.dp, Rose.Primary.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .clickableWithTooltip(
                onPressChange = { pressed = it },
                onClick = { Session.gotoBoundary(targetAddress, isUpward = isUpward) }
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "${tr("转到此地址：")}$addrHex",
            style = monoStyle(13, Rose.Primary, weight = FontWeight.SemiBold)
        )
    }
}

/**
 * 左上角仅显示跳转地址 `0x...`，点击该地址自动定位到该地址（界面调整 5：已移除右侧快捷按钮）
 */
@Composable
private fun MemoryMiniBar(
    ui: Ui,
    onLocateAddress: () -> Unit
) {
    val displayAddr = ui.memoryHighlightAddress ?: ui.memoryBase
    val addrHexText = remember(displayAddr) {
        "0x" + java.lang.Long.toHexString(displayAddr).uppercase()
    }
    var addrPressed by remember { mutableStateOf(false) }
    val addrScale by animateFloatAsState(if (addrPressed) 0.93f else 1f, Motion.Fast, label = "addrJumpScale")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .scale(addrScale)
                .background(Rose.CardHigh.copy(alpha = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)), RoundedCornerShape(6.dp))
                .clickableWithTooltip(
                    onPressChange = { addrPressed = it },
                    onClick = onLocateAddress
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = addrHexText,
                style = monoStyle(12, Rose.Primary, weight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.weight(1f))
        val formatSummary = remember(ui.valueMask) { Formatter.maskSummary(ui.valueMask) }
        val hasAsmFormat = remember(ui.valueMask) { Formatter.activeAsmArch(ui.valueMask) != null }
        Box(
            Modifier
                .background(
                    if (hasAsmFormat) Rose.ChipSelected else Rose.CardHigh.copy(alpha = ui.metrics.bgOpacity.coerceIn(0.25f, 1.0f)),
                    RoundedCornerShape(6.dp)
                )
                .clickableWithTooltip(tooltip = tr("值格式")) {
                    Session.openSheet(Sheet.VALUE_FORMAT)
                }
                .padding(horizontal = 7.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatSummary,
                style = monoStyle(11, if (hasAsmFormat) Rose.Cyan else Rose.TextSecond, weight = FontWeight.Medium)
            )
        }
    }
}

@Composable
private fun MemoryRow(
    item: MemoryItem,
    checked: Boolean,
    mask: Int,
    disasmText: String?,
    isJumpTarget: Boolean
) {
    val currentItem by rememberUpdatedState(item)
    val bgOpacity = com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val isDark = Rose.IsDark
    val accent = remember(item.flags, isDark) { typeColor(item.flags) }
    val hexColor = remember(item.pointer, isDark) { pointerColor(item.pointer) }
    val ptrLabel = remember(item.pointer) { pointerTypeLabel(item.pointer) }
    val shortType = remember(item.flags) { com.littlecodesauce.ggnext.model.ValueType.shortName(item.flags) }
    val formattedVal = remember(item.value, item.flags) { valueText(item) }
    val hexVal = remember(item.value, item.size) { Formatter.hex(item.value, item.size) + "h" }
    val asciiVal = remember(item.value, item.size, mask) {
        if (mask and Formatter.F_STRING != 0) Formatter.bytesToAscii(item.value, item.size) else ""
    }
    val asmArch = remember(mask) { Formatter.activeAsmArch(mask) }
    val resolvedAsm = remember(disasmText, asmArch, item.address, item.value) {
        if (asmArch == null) ""
        else if (!disasmText.isNullOrBlank()) disasmText
        else com.littlecodesauce.ggnext.engine.AsmEngineManager.disassembleWord(asmArch, item.address, item.value, 4)
    }

    val rowBgColor = when {
        isJumpTarget && checked -> Rose.JumpHighlight.copy(alpha = 0.42f)
        isJumpTarget -> Rose.JumpHighlight
        checked -> Rose.Primary.copy(alpha = if (isDark) 0.18f else 0.20f)
        else -> Rose.Row.copy(alpha = (bgOpacity * 0.88f).coerceIn(0.18f, 1.0f))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBgColor, RoundedCornerShape(8.dp))
            .then(
                if (isJumpTarget) Modifier.border(1.dp, Rose.JumpBorder, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickableLongPress(
                onClick = { Session.toggle(currentItem) },
                onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgCheckBox(
            checked = checked,
            onCheckedChange = { Session.toggle(currentItem) },
            size = 19.dp,
            accentColor = Rose.Primary
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.cachedAddressHex,
                    style = monoStyle(
                        size = 13,
                        color = Rose.Primary,
                        weight = if (isJumpTarget) FontWeight.Bold else FontWeight.Normal
                    )
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hexVal,
                    style = monoStyle(
                        size = 12,
                        color = hexColor,
                        weight = if (item.pointer != 0 && item.pointer != 4) FontWeight.SemiBold else FontWeight.Normal
                    )
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableLongPress(
                        onClick = { Session.openSheet(Sheet.EDITOR, currentItem, singleEdit = true) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
                ) {
                    Text(text = formattedVal, style = monoStyle(13, accent, weight = FontWeight.Medium))
                    Spacer(Modifier.width(2.dp))
                    Text(text = shortType, style = monoStyle(13, accent, weight = FontWeight.ExtraBold))
                }
                if (asciiVal.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("'$asciiVal'", style = monoStyle(11, Rose.Yellow, weight = FontWeight.Medium))
                }
            }
            if (resolvedAsm.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableLongPress(
                        onClick = { Session.openSheet(Sheet.WRITE_ASM, currentItem) },
                        onLongPress = { Session.openSheet(Sheet.CONTEXT, currentItem) }
                    )
                ) {
                    Text(
                        text = "${asmArch?.shortCode ?: "ASM"} ",
                        style = monoStyle(10.5f.toInt(), Rose.Primary, weight = FontWeight.Bold)
                    )
                    Text(
                        text = resolvedAsm,
                        style = monoStyle(12, Rose.Cyan, weight = FontWeight.Medium),
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (ptrLabel.isNotEmpty()) {
            Text(
                text = ptrLabel,
                style = monoStyle(11, hexColor, weight = FontWeight.Bold)
            )
        } else {
            val regColor = remember(item.region, isDark) { com.littlecodesauce.ggnext.ui.theme.regionColor(item.region) }
            Text(
                text = item.region,
                style = monoStyle(11, regColor, weight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun EmptyMemory() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Code, null, tint = Rose.TextDim, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            tr("内存编辑器\n使用工具栏「转到地址」加载内存视图。"),
            color = Rose.TextSecond,
            fontSize = fsp(14f),
            lineHeight = fsp(21f),
            textAlign = TextAlign.Center
        )
    }
}
