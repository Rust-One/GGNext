package com.littlecodesauce.ggnext.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.littlecodesauce.ggnext.ui.theme.GgBorder
import com.littlecodesauce.ggnext.ui.theme.GgControlHeight
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.GgShapes
import com.littlecodesauce.ggnext.ui.theme.GgSpacing
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Motion
import com.littlecodesauce.ggnext.ui.theme.Rose

/**
 * 全局统一分组标题（替代各页面独立实现的 SectionLabel / HomeSectionTitle）。
 */
@Composable
fun GgSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Rose.Primary,
    fontSize: Float = 13f,
    startPadding: Dp = GgSpacing.Md
) {
    Text(
        text = tr(text),
        color = color,
        fontSize = fsp(fontSize),
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(start = startPadding, top = GgSpacing.Xs, bottom = GgSpacing.Xxs)
    )
}

/**
 * 全局统一圆角卡片容器（支持按 bgOpacity 联动透明度和自定义边框/圆角变体）。
 */
@Composable
fun GgSurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = GgShapes.Xl,
    backgroundColor: Color = Rose.Card,
    borderColor: Color = Rose.Divider,
    borderWidth: Dp = GgBorder.Default,
    contentPadding: PaddingValues = PaddingValues(vertical = GgSpacing.Xs),
    respectBgOpacity: Boolean = false,
    bgOpacityScale: Float = 0.90f,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val resolvedBg = if (respectBgOpacity) {
        backgroundColor.copy(alpha = (bgOpacity * bgOpacityScale).coerceIn(0.20f, 1.0f))
    } else {
        backgroundColor
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(resolvedBg)
            .then(
                if (borderWidth > 0.dp && borderColor.alpha > 0f) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 全局统一列表分隔线（替代分散的 RowDivider / FilterDivider / 内联分隔条）。
 */
@Composable
fun GgDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = GgSpacing.Xl,
    endInset: Dp = 14.dp,
    thickness: Dp = GgBorder.Default,
    color: Color = Rose.Divider
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset, end = endInset)
            .height(thickness)
            .background(color)
    )
}

/**
 * 全局统一状态徽章 / 轻量标签按钮（替代零散的标签胶囊、操作小按钮）。
 */
@Composable
fun GgBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Rose.Primary,
    backgroundColor: Color = textColor.copy(alpha = 0.16f),
    borderColor: Color = Color.Transparent,
    shape: Shape = GgShapes.Sm,
    fontSize: Float = 11.5f,
    monospace: Boolean = false,
    fontWeight: FontWeight = FontWeight.Medium,
    horizontalPadding: Dp = 9.dp,
    verticalPadding: Dp = 4.dp,
    translate: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val label = if (translate) tr(text) else text
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderColor.alpha > 0f) Modifier.border(GgBorder.Thin, borderColor, shape)
                else Modifier
            )
            .then(if (onClick != null) Modifier.clickableWithTooltip(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        if (monospace) {
            Text(text = label, style = monoStyle(fontSize.toInt(), textColor, weight = fontWeight), maxLines = 1)
        } else {
            Text(
                text = label,
                color = textColor,
                fontSize = fsp(fontSize),
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    }
}

/**
 * 全局统一分段选项 / 选项卡按钮（替代 ToolbarSheet、DebugScreen、DebugSheets、AppearanceSheet 等各处独立实现的分段按钮）。
 */
@Composable
fun GgSegmentTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = GgControlHeight.Chip,
    shape: Shape = GgShapes.Md,
    accent: Color = Rose.Primary,
    selectedBackground: Color = Rose.ChipSelected,
    unselectedBackground: Color = Rose.CardHigh,
    showBorder: Boolean = true,
    fontSize: Float = 12.5f,
    monospace: Boolean = false,
    translate: Boolean = true,
    horizontalPadding: Dp = GgSpacing.Md,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> unselectedBackground.copy(alpha = 0.4f)
        selected -> selectedBackground
        else -> unselectedBackground
    }
    val textColor = when {
        !enabled -> Rose.TextDim
        selected -> accent
        else -> Rose.TextSecond
    }
    val weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    val displayText = if (translate) tr(label) else label
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .then(
                if (showBorder) {
                    Modifier.border(
                        width = if (selected && enabled) GgBorder.Emphasis else GgBorder.Thin,
                        color = if (selected && enabled) accent else Rose.Divider,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            .clickableWithTooltip(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        if (monospace) {
            Text(text = displayText, style = monoStyle(fontSize.toInt(), textColor, weight = weight), maxLines = 1)
        } else {
            Text(
                text = displayText,
                color = textColor,
                fontSize = fsp(fontSize),
                fontWeight = weight,
                maxLines = 1
            )
        }
    }
}

/**
 * 全局统一图标动作按钮（支持悬停提示、按压缩放、可选底色容器）。
 */
@Composable
fun GgIconActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Rose.TextSecond,
    boxSize: Dp = 30.dp,
    iconSize: Dp = GgIconSize.Md,
    backgroundColor: Color = Color.Transparent,
    shape: Shape = GgShapes.Sm,
    tooltip: String = contentDescription.orEmpty(),
    enabled: Boolean = true
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed && enabled) 0.90f else 1f, Motion.Fast, label = "iconActScale")
    Box(
        modifier = modifier
            .size(boxSize)
            .scale(scale)
            .clip(shape)
            .then(if (backgroundColor.alpha > 0f) Modifier.background(backgroundColor) else Modifier)
            .clickableWithTooltip(
                tooltip = tooltip,
                enabled = enabled,
                onPressChange = { pressed = it },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription?.let { tr(it) },
            tint = if (enabled) tint else Rose.TextDim,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 全局统一设置/列表导航行（通过 iconInBox 切换悬浮设置页带底框图标风格与主页简洁图标风格）。
 */
@Composable
fun GgSettingNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconInBox: Boolean = true,
    badgeText: String? = null,
    badgeColor: Color = Rose.Primary,
    respectBgOpacity: Boolean = true,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = Motion.Fast,
        label = "settingNavRowScale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.74f else 1f,
        animationSpec = Motion.Fast,
        label = "settingNavRowAlpha"
    )
    val hPad = if (iconInBox) 14.dp else 18.dp
    val vPad = if (iconInBox) 11.dp else 14.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(pressScale)
            .alpha(pressAlpha)
            .clickableWithTooltip(onPressChange = { pressed = it }, onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconInBox) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(GgShapes.Md)
                    .background(Rose.CardHigh.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f))),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Rose.Primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
        } else {
            Icon(icon, null, tint = Rose.TextSecond, modifier = Modifier.size(GgIconSize.Xl))
            Spacer(Modifier.width(GgSpacing.Xl))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(if (iconInBox) 15f else 15.5f),
                fontWeight = FontWeight.Medium
            )
            if (!iconInBox) Spacer(Modifier.height(GgSpacing.Xxs))
            Text(
                text = tr(subtitle),
                color = Rose.TextSecond,
                fontSize = fsp(if (iconInBox) 12f else 12.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (badgeText != null) {
            Spacer(Modifier.width(GgSpacing.Md))
            GgBadge(text = badgeText, textColor = badgeColor)
        } else {
            Spacer(Modifier.width(GgSpacing.Sm))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Rose.TextDim,
                modifier = Modifier.size(if (iconInBox) GgIconSize.Md else GgIconSize.Lg)
            )
        }
    }
}

/**
 * 全局统一开关行（统一采用 GgSwitch，消除原生 Switch 与手工 Row 碎片化）。
 */
@Composable
fun GgSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    containerBackground: Color = Color.Transparent,
    shape: Shape = GgShapes.Md,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 11.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (containerBackground.alpha > 0f) {
                    Modifier
                        .clip(shape)
                        .background(containerBackground)
                } else {
                    Modifier
                }
            )
            .clickableWithTooltip { onCheckedChange(!checked) }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(if (subtitle != null) 13.5f else 15f),
                fontWeight = if (subtitle != null) FontWeight.Medium else FontWeight.Normal
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = tr(subtitle),
                    color = Rose.TextSecond,
                    fontSize = fsp(11f)
                )
            }
        }
        Spacer(Modifier.width(GgSpacing.Md))
        GgSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 全局统一纤细波浪滑条行（供设置页与外观页共同复用，消除跨层引用）。
 */
@Composable
fun GgSettingSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = GgSpacing.Sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(title), color = Rose.Text, fontSize = fsp(14.5f), modifier = Modifier.weight(1f))
            GgBadge(
                text = display,
                textColor = Rose.TextSecond,
                backgroundColor = Rose.CardHigh.copy(alpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f)),
                shape = GgShapes.Xs,
                fontSize = 12f,
                fontWeight = FontWeight.Normal,
                horizontalPadding = 9.dp,
                verticalPadding = 3.dp,
                translate = false
            )
        }
        Spacer(Modifier.height(GgSpacing.Xxs))
        WavySlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            activeColor = Rose.Primary,
            inactiveColor = Rose.CardHigh.copy(alpha = bgOpacity)
        )
    }
}

/**
 * 全局统一键值信息行（图标 + 标题 + 右侧值）。
 */
@Composable
fun GgSettingInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
        Spacer(Modifier.width(GgSpacing.Xl))
        Text(
            text = tr(title),
            color = Rose.Text,
            fontSize = fsp(14.5f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(GgSpacing.MdPlus))
        Text(
            text = value,
            color = Rose.TextSecond,
            fontSize = fsp(13f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 全局统一单行输入容器（支持可选 0x 前缀、错误态边框、尾部操作槽）。
 */
@Composable
fun GgInputBox(
    modifier: Modifier = Modifier,
    height: Dp = GgControlHeight.Input,
    shape: Shape = GgShapes.Md,
    backgroundColor: Color = Rose.CardHigh,
    borderColor: Color = Rose.Divider,
    borderWidth: Dp = GgBorder.Default,
    horizontalPadding: Dp = GgSpacing.Lg,
    prefix: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (borderWidth > 0.dp && borderColor.alpha > 0f) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix != null) {
            prefix()
        }
        content()
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * 全局统一居中模态遮罩层（带背景暗幕与弹窗缩放动效）。
 */
@Composable
fun GgModalOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    zIndex: Float = 20f,
    dismissOnScrimClick: Boolean = true,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
            .fillMaxSize()
            .zIndex(zIndex)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Rose.Scrim)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    if (dismissOnScrimClick) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = dialogEnter(),
                exit = dialogExit()
            ) {
                Box(
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {}
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 全局统一二次确认弹窗（基于 GgModalOverlay + GgSurfaceCard + RoseButton）。
 */
@Composable
fun GgConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelLabel: String = "取消"
) {
    GgModalOverlay(
        visible = visible,
        onDismiss = onDismiss
    ) {
        GgSurfaceCard(
            modifier = Modifier.padding(horizontal = 24.dp),
            shape = GgShapes.Xl,
            contentPadding = PaddingValues(18.dp)
        ) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(17f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(GgSpacing.Md))
            Text(
                text = tr(body),
                color = Rose.TextSecond,
                fontSize = fsp(13.5f),
                lineHeight = fsp(19.5f)
            )
            Spacer(Modifier.height(GgSpacing.Xl))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GgSpacing.MdPlus)
            ) {
                RoseButton(
                    label = cancelLabel,
                    modifier = Modifier
                        .weight(1f)
                        .height(GgControlHeight.Button)
                ) {
                    onDismiss()
                }
                RoseButton(
                    label = confirmLabel,
                    filled = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(GgControlHeight.Button)
                ) {
                    onConfirm()
                }
            }
        }
    }
}

/**
 * 全局统一空状态占位视图（支持简约图标风格与带底框+CTA按钮风格）。
 */
@Composable
fun GgEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    iconInBadge: Boolean = false,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (iconInBadge) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(GgShapes.Xl)
                    .background(Rose.Primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title?.let { tr(it) },
                    tint = Rose.Primary,
                    modifier = Modifier.size(GgIconSize.Hero)
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = title?.let { tr(it) },
                tint = Rose.TextDim,
                modifier = Modifier.size(GgIconSize.Empty)
            )
        }
        Spacer(Modifier.height(if (iconInBadge) GgSpacing.Lg else 14.dp))
        if (!title.isNullOrBlank()) {
            Text(
                text = tr(title),
                color = Rose.Text,
                fontSize = fsp(15.5f),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(GgSpacing.Sm))
        }
        Text(
            text = tr(message),
            color = Rose.TextSecond,
            fontSize = fsp(if (title != null) 12f else 14f),
            lineHeight = fsp(if (title != null) 18f else 21f),
            textAlign = TextAlign.Center
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(GgSpacing.Xl))
            RoseButton(
                label = actionLabel,
                filled = true,
                enabled = actionEnabled,
                modifier = Modifier.height(GgControlHeight.Chip)
            ) {
                onAction()
            }
        }
    }
}
