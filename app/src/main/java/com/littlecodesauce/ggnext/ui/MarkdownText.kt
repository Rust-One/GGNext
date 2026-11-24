package com.littlecodesauce.ggnext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染器（零外部依赖）。
 *
 * AI 服务返回的 Markdown 此前以裸文本显示，`###`、`**`、`|表格|` 等标记直接暴露，
 * 观感差且难读。本渲染器支持对话场景实际出现的全部常用语法：
 * 标题、加粗、斜体、删除线、行内代码、代码块、有序/无序列表、引用、表格、分隔线。
 * 表格与代码块可横向滚动，避免撑破悬浮窗气泡。
 */
@Composable
internal fun MarkdownText(
    text: String,
    color: Color,
    accent: Color,
    secondary: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val blocks = rememberMarkdownBlocks(text)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val scale = when (block.level) {
                        1 -> 1.35f
                        2 -> 1.22f
                        3 -> 1.12f
                        else -> 1.04f
                    }
                    Text(
                        text = inline(block.content, color, accent, secondary),
                        color = accent,
                        fontSize = fontSize * scale,
                        fontWeight = FontWeight.Bold
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = inline(block.lines.joinToString("\n"), color, accent, secondary),
                        color = color,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.45f
                    )
                }

                is MdBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = (block.indent * 12).dp)) {
                        Text(
                            text = block.marker,
                            color = accent,
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 20.dp)
                        )
                        Text(
                            text = inline(block.content, color, accent, secondary),
                            color = color,
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.4f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Quote -> {
                    Row {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(16.dp)
                                .background(accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inline(block.content, color, accent, secondary),
                            color = secondary,
                            fontSize = fontSize,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }

                is MdBlock.Code -> {
                    // 代码块：整体可横向滚动，超长时纵向滚动并限高，避免撑破气泡 / 面板
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(secondary.copy(alpha = 0.10f))
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                text = block.lang,
                                color = accent,
                                fontSize = fontSize * 0.85f,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        block.lines.forEach { line ->
                            Text(
                                text = line.ifBlank { " " },
                                color = color,
                                fontSize = fontSize * 0.92f,
                                fontFamily = FontFamily.Monospace,
                                softWrap = false
                            )
                        }
                    }
                }

                is MdBlock.Table -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .border(1.dp, secondary.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    ) {
                        TableRow(block.header, fontSize, color, accent, secondary, header = true)
                        block.rows.forEach { row ->
                            TableRow(row, fontSize, color, accent, secondary, header = false)
                        }
                    }
                }

                MdBlock.Divider -> HorizontalDivider(color = secondary.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    fontSize: TextUnit,
    color: Color,
    accent: Color,
    secondary: Color,
    header: Boolean
) {
    Row {
        cells.forEach { cell ->
            Box(
                Modifier
                    .widthIn(min = 64.dp, max = 220.dp)
                    .border(1.dp, secondary.copy(alpha = 0.22f))
                    .background(if (header) accent.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = inline(cell, color, accent, secondary),
                    color = if (header) accent else color,
                    fontSize = fontSize * 0.92f,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 4
                )
            }
        }
    }
}

/** 解析结果：整段文本按块拆分，渲染时逐块绘制。 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock
    data class Paragraph(val lines: List<String>) : MdBlock
    data class ListItem(val indent: Int, val marker: String, val content: String) : MdBlock
    data class Quote(val content: String) : MdBlock
    data class Code(val lang: String, val lines: List<String>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

private val HEADING_RE = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
private val LIST_RE = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
private val QUOTE_RE = Regex("""^\s*>\s?(.*)$""")
private val DIVIDER_RE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
private val TABLE_SEP_RE = Regex("""^\s*\|?[\s:|-]+\|[\s:|-]*$""")

@Composable
private fun rememberMarkdownBlocks(text: String): List<MdBlock> =
    androidx.compose.runtime.remember(text) { parseMarkdown(text) }

private fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split("\n")
    val out = ArrayList<MdBlock>()
    var i = 0
    var para = ArrayList<String>()

    fun flushPara() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Paragraph(para.toList()))
            para.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]

        // 代码块 ```
        if (line.trimStart().startsWith("```")) {
            flushPara()
            val lang = line.trim().removePrefix("```").trim()
            val body = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                body.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.Code(lang, body))
            continue
        }

        // 表格：表头行 + 分隔行 + 数据行
        if (line.contains('|') && i + 1 < lines.size &&
            lines[i + 1].contains('|') && TABLE_SEP_RE.matches(lines[i + 1])
        ) {
            flushPara()
            val header = splitRow(line)
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows.add(splitRow(lines[i]))
                i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }

        when {
            HEADING_RE.matches(line) -> {
                flushPara()
                val m = HEADING_RE.matchEntire(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]))
            }

            DIVIDER_RE.matches(line) -> {
                flushPara()
                out.add(MdBlock.Divider)
            }

            QUOTE_RE.matches(line) -> {
                flushPara()
                out.add(MdBlock.Quote(QUOTE_RE.matchEntire(line)!!.groupValues[1]))
            }

            LIST_RE.matches(line) -> {
                flushPara()
                val m = LIST_RE.matchEntire(line)!!
                val indent = m.groupValues[1].length / 2
                val rawMarker = m.groupValues[2]
                val marker = if (rawMarker.length == 1) "•" else "$rawMarker."
                out.add(MdBlock.ListItem(indent, marker, m.groupValues[3]))
            }

            line.isBlank() -> flushPara()

            else -> para.add(line)
        }
        i++
    }
    flushPara()
    return out
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/** 行内样式：粗体 / 斜体 / 删除线 / 行内代码 / 链接。 */
private val INLINE_RE =
    Regex("""(\*\*[^*\n]+\*\*|\*[^*\n]+\*|`[^`\n]+`|~~[^~\n]+~~|\[[^\]\n]+\]\([^)\n]+\))""")

private fun inline(raw: String, color: Color, accent: Color, secondary: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        INLINE_RE.findAll(raw).forEach { m ->
            if (m.range.first > cursor) append(raw.substring(cursor, m.range.first))
            val token = m.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(token.removePrefix("**").removeSuffix("**"))
                }

                token.startsWith("`") -> withStyle(
                    SpanStyle(
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        background = secondary.copy(alpha = 0.16f)
                    )
                ) {
                    append(token.removePrefix("`").removeSuffix("`"))
                }

                token.startsWith("~~") -> withStyle(
                    SpanStyle(color = secondary, textDecoration = TextDecoration.LineThrough)
                ) {
                    append(token.removePrefix("~~").removeSuffix("~~"))
                }

                token.startsWith("[") -> {
                    val idx = token.indexOf("](")
                    val label = token.substring(1, idx)
                    withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) {
                        append(label)
                    }
                }

                else -> withStyle(SpanStyle(color = color, fontStyle = FontStyle.Italic)) {
                    append(token.removePrefix("*").removeSuffix("*"))
                }
            }
            cursor = m.range.last + 1
        }
        if (cursor < raw.length) append(raw.substring(cursor))
    }
