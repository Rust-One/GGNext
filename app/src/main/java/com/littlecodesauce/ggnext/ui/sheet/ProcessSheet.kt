package com.littlecodesauce.ggnext.ui.sheet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.data.AppIconCache
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.ui.components.Chip
import com.littlecodesauce.ggnext.ui.components.GgInputBox
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.ItemEnter
import com.littlecodesauce.ggnext.ui.components.NextSheetCard
import com.littlecodesauce.ggnext.ui.components.VerticalFastScrollbar
import com.littlecodesauce.ggnext.ui.components.clickableNoRipple
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.monoStyle
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

@Composable
fun ProcessSheet(ui: Ui) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(ui.workMode, ui.hasRootPermission) {
        (ctx.applicationContext as? android.app.Application)?.let { app ->
            Session.loadProcesses(app)
        }
    }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val list = remember(ui.processes, ui.processFilter, ui.processSortBySize, query.text) {
        val filteredByCategory = when (ui.processFilter) {
            1 -> ui.processes.filter { !it.system }
            2 -> ui.processes.filter { it.system }
            else -> ui.processes
        }
        val sorted = if (ui.processSortBySize) {
            filteredByCategory.sortedByDescending { it.sizeBytes }
        } else {
            filteredByCategory.sortedWith(
                compareBy<TargetProcess> { it.system }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.pid }
            )
        }
        if (query.text.isBlank()) sorted
        else sorted.filter {
            it.label.contains(query.text, true) || it.packageName.contains(query.text, true) ||
                it.pid.toString().contains(query.text)
        }
    }

    val listState = rememberLazyListState()

    NextSheetCard(
        title = "进程列表",
        subtitle = "选择要附加的进程",
        onDismiss = { Session.popSheet() },
        maxBodyHeight = 640
    ) {
        Column {
            GgInputBox(
                modifier = Modifier.padding(horizontal = GgSpacing.Xs, vertical = GgSpacing.Xs),
                height = GgControlHeight.Button,
                shape = GgShapes.Md,
                borderWidth = 0.dp,
                horizontalPadding = GgSpacing.Lg,
                prefix = {
                    Icon(Icons.Rounded.Search, null, tint = Rose.TextDim, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(GgSpacing.Md))
                }
            ) {
                GgTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).height(32.dp),
                    textStyle = TextStyle(color = Rose.Text, fontSize = fsp(14f)),
                    cursorColor = Rose.Primary,
                    suppressSystemKeyboard = false,
                    placeholder = "搜索名称 / 包名 / pid"
                )
            }
            // 界面问题 2：去除“优先级”按钮，同时“大小”按钮支持与“全部/用户/系统”多选共存
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "全部",
                    1 to "用户",
                    2 to "系统"
                ).forEach { (idx, label) ->
                    Chip(
                        label = label,
                        selected = ui.processFilter == idx,
                        modifier = Modifier.weight(1f)
                    ) {
                        Session.setProcessFilter(idx)
                    }
                }
                Chip(
                    label = "大小",
                    selected = ui.processSortBySize,
                    modifier = Modifier.weight(1f)
                ) {
                    Session.toggleProcessSortBySize()
                }
            }
            if (ui.processLoading) {
                SkeletonList()
            } else {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(list, key = { _, p -> p.pid }) { idx, p ->
                            ProcessRow(p, idx) { Session.attach(p) }
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
}

@Composable
private fun ProcessRow(p: TargetProcess, index: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, Motion.Fast, label = "proc")
    ItemEnter(p.packageName + p.pid, index) {
        Row(
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .background(Rose.Row, GgShapes.Lg)
                .clickableNoRipple(interaction, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(p)
            Spacer(Modifier.width(12.dp))
            Text("[${p.pid}]", style = monoStyle(13, Rose.Green))
            Spacer(Modifier.width(6.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 界面问题 4：虚拟空间中的进程在进程名左侧显示蓝色 '*'
                if (p.isVirtual) {
                    Text(
                        text = "*",
                        color = Rose.VirtualBadge,
                        fontSize = fsp(16f),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(3.dp))
                }
                // 界面问题 3：支持展示完整子进程名称（最高 2 行），不强制截断丢失子进程后缀
                Text(
                    text = p.label,
                    color = Rose.Text,
                    fontSize = fsp(14.5f),
                    lineHeight = fsp(18f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("[${if (p.x64) "x64" else "x86"}]", style = monoStyle(12, Rose.TextSecond))
            Spacer(Modifier.width(4.dp))
            Text("[${ProcessScanner.sizeText(p.sizeBytes)}]", style = monoStyle(12, Rose.TextSecond))
        }
    }
}

@Composable
private fun AppIcon(p: TargetProcess) {
    val ctx = LocalContext.current
    val bmp = remember(p.packageName, p.apkPath) { AppIconCache.get(ctx, p.packageName, p.apkPath) }
    Box(
        Modifier
            .size(42.dp)
            .clip(GgShapes.Lg)
            .background(Rose.CardHigh),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) Image(bitmap = bmp, contentDescription = p.label, modifier = Modifier.size(42.dp))
        else Text(p.label.take(1).uppercase(), color = Rose.Primary, fontSize = fsp(18f))
    }
}

@Composable
private fun SkeletonList() {
    val transition = rememberInfiniteTransition(label = "sk")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(820), RepeatMode.Reverse),
        label = "skA"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .alpha(alpha)
                    .background(Rose.Row, GgShapes.Lg)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(42.dp).background(Rose.CardHigh, GgShapes.Lg))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(140.dp).height(11.dp).background(Rose.CardHigh, GgShapes.Xxs))
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(84.dp).height(9.dp).background(Rose.CardHigh, GgShapes.Xxs))
                }
            }
        }
    }
}
