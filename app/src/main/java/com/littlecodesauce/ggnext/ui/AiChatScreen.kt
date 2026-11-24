package com.littlecodesauce.ggnext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.littlecodesauce.ggnext.ai.AiChatEngine
import com.littlecodesauce.ggnext.ai.AiClient
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.core.Ui
import com.littlecodesauce.ggnext.ui.components.GgTextField
import com.littlecodesauce.ggnext.ui.components.RoseButton
import com.littlecodesauce.ggnext.ui.components.fsp
import com.littlecodesauce.ggnext.ui.components.tr
import com.littlecodesauce.ggnext.ui.theme.GgIconSize
import com.littlecodesauce.ggnext.ui.theme.LocalUiMetrics
import com.littlecodesauce.ggnext.ui.theme.Rose
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 工具消息解析结果：工具名 + 结果正文 + 折叠时的单行摘要。 */
private data class ToolInfo(val name: String, val body: String, val summary: String)

/** 解析引擎追加的 "[工具] name：body" / "[工具发现] ..." 消息。 */
private fun parseToolMessage(content: String): ToolInfo {
    val isDiscovery = content.startsWith("[工具发现]")
    val body = content.removePrefix("[工具] ").removePrefix("[工具发现] ")
    val idx = body.indexOf('：')
    val name = when {
        isDiscovery -> tr("工具发现")
        idx in 1..48 -> body.substring(0, idx).trim()
        else -> tr("工具调用")
    }
    val result = if (idx in 1..48) body.substring(idx + 1) else body
    // 折叠摘要：优先 JSON 里的关键字段，否则取首行
    val firstLine = result.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    val summary = runCatching {
        val js = JSONObject(result)
        val keys = listOf("summary", "message", "error", "total", "count", "attached", "pid")
        val picked = keys.firstNotNullOfOrNull { k -> if (js.has(k)) "$k=${js.get(k)}" else null }
        picked ?: firstLine
    }.getOrDefault(firstLine)
    return ToolInfo(name, result, summary.take(80))
}

/**
 * 实时对话推荐：不再使用固定快捷词，而是综合三处上下文动态生成——
 * 1. 用户正在输入的内容（关键词命中进程/搜索/断点/汇编/读写等场景）；
 * 2. 当前 GGNext 状态（是否已附加进程、搜索结果/保存列表/断点数量）；
 * 3. 最近一条 AI 回复中「接下来/建议/可以/例如」引出的后续动作。
 * 结果随输入与对话推进实时刷新，点击即填入输入框。
 */
private fun buildChatSuggestions(
    ui: Ui,
    messages: List<AiClient.ChatMessage>,
    input: String
): List<String> {
    val out = LinkedHashSet<String>()
    val hasTarget = ui.attachedOk && ui.target != null
    val resultCount = ui.results.size
    val savedCount = ui.saved.size
    val bpCount = ui.breakpoints.size

    when {
        input.contains("进程") || input.contains("附加") || input.contains("切换") ||
        input.contains("微信") || input.contains("wechat", true) -> {
            out.add("列出正在运行的进程")
            if (hasTarget) out.add("查看当前状态")
            out.add("附加微信进程")
        }

        input.contains("搜索") || input.contains("扫") || input.contains("数值") ||
        input.contains("search", true) -> {
            out.add("搜索内存中的整数")
            out.add("联合搜索多个条件")
            if (resultCount > 0) out.add("分析当前搜索结果")
        }

        input.contains("断点") || input.contains("bp", true) -> {
            out.add("列出可用的断点工具")
            if (hasTarget) out.add("给当前进程设置硬件断点")
        }

        input.contains("汇编") || input.contains("反汇编") || input.contains("asm", true) -> {
            out.add("把这段汇编转成机器码")
            out.add("反汇编当前地址")
        }

        input.contains("读") || input.contains("写") || input.contains("内存") -> {
            if (hasTarget) out.add("读取当前进程指定地址的内存")
            out.add("查看当前状态")
        }
    }

    if (!hasTarget) {
        out.add("列出正在运行的进程")
        out.add("查看当前状态")
    } else {
        out.add("查看当前状态")
        if (resultCount > 0) out.add("分析当前搜索结果")
        if (savedCount > 0) out.add("查看保存列表")
        if (bpCount > 0) out.add("列出当前断点")
        out.add("列出内存区域")
    }

    // 从最近 AI 回复里提取可立即执行的后续动作，形成「对话接着对话」的推荐
    val lastAi = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
    lastAi?.content?.let { content ->
        Regex("""(?:接下来|建议|可以|例如|比如)[^。！？\n]{4,36}""")
            .findAll(content)
            .map { it.value.replace(Regex("""^(接下来|建议|可以|例如|比如)[:：\s]*"""), "") }
            .map { it.trim(' ', '，', '。', '：', ':') }
            .filter { it.length in 4..30 && !it.contains("|") }
            .forEach { out.add(it) }
    }

    if (out.isEmpty()) {
        out.add("查看当前状态")
        out.add("列出正在运行的进程")
        out.add("搜索内存中的整数")
    }
    return out.take(6).toList()
}

/**
 * 悬浮窗内独立的 AI 对话页（导航栏「调试」旁的「对话」tab）。
 *
 * v0.9.1 关键修复：所有运行状态与请求协程均由全局 [AiChatEngine] 持有，
 * 本 Composable 只做展示与交互。因此切换 tab、关闭悬浮窗、息屏后 AI 仍继续输出，
 * 回到对话页即可看到完整结果与待确认弹窗。
 */
@Composable
internal fun AiChatScreen(ui: Ui) {
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val clipboard = LocalClipboardManager.current
    val appContext = LocalContext.current.applicationContext
    val bgOpacity = LocalUiMetrics.current.bgOpacity.coerceIn(0.25f, 1.0f)
    val cardAlpha = (bgOpacity * 0.92f).coerceIn(0.22f, 1.0f)
    // 横屏（屏幕高度 < 480dp）时气泡放宽，避免竖屏 280dp 上限导致横屏文字堆叠
    val configuration = LocalConfiguration.current
    val isLandscapeAi = configuration.screenHeightDp < 480
    val bubbleMaxWidth = if (isLandscapeAi) 620.dp else 280.dp

    // ---- 持久化配置（仅本页编辑用，实际发送由引擎从 Session 实时读取）----
    val savedBaseUrl = ui.settings["ai_base_url"].orEmpty()
    val savedApiKey = ui.settings["ai_api_key"].orEmpty()
    val savedModel = ui.settings["ai_model"].orEmpty()
    val savedModelsCsv = ui.settings["ai_models"].orEmpty()

    var baseUrl by remember(savedBaseUrl) { mutableStateOf(savedBaseUrl) }
    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var models by remember(savedModelsCsv) {
        mutableStateOf(savedModelsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    }
    var showConfig by remember { mutableStateOf(savedBaseUrl.isBlank() || savedModel.isBlank()) }
    var isFetching by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var autoExecute by remember { mutableStateOf(AiChatEngine.autoExecute()) }
    var streamEnabled by remember { mutableStateOf(AiChatEngine.streamEnabled()) }
    var onDemandTools by remember { mutableStateOf(AiChatEngine.onDemandTools()) }
    var showSessions by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }

    // ---- 对话状态（全部来自全局引擎）----
    val messages = AiChatEngine.messages
    val sending = AiChatEngine.sending
    val error = AiChatEngine.error
    val currentTool = AiChatEngine.currentTool
    val pendingTool = AiChatEngine.pendingTool
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val attachResults = AiChatEngine.attachResults
    val listState = rememberLazyListState()
    // 工具调用折叠状态：用消息索引集合，切 tab 重建后默认折叠（不影响正文）
    var expandedTools by remember { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun persistConfig() {
        Session.setSetting("ai_base_url", baseUrl.trim())
        Session.setSetting("ai_api_key", apiKey.trim())
        Session.setSetting("ai_model", model.trim())
        Session.setSetting("ai_models", models.joinToString(","))
    }

    fun fetchModels() {
        if (isFetching) return
        isFetching = true
        scope.launch {
            val r = AiClient.fetchModels(baseUrl, apiKey)
            isFetching = false
            if (r.ok && !r.data.isNullOrEmpty()) {
                models = r.data
                Session.setSetting("ai_models", r.data.joinToString(","))
                if (model.isBlank() || r.data.contains(model)) {
                    model = r.data.first()
                    Session.setSetting("ai_model", model)
                }
                showModelPicker = true
                Session.toast(tr("已获取模型") + " ${r.data.size}")
            } else {
                AiChatEngine.dismissError()
            }
        }
    }

    fun send() {
        AiChatEngine.send(appContext, input.text.trim(), attachResults)
        input = TextFieldValue("")
    }

    fun clearChat() {
        AiChatEngine.clear()
        Session.toast(tr("对话已清空"))
    }

    fun copyAll() {
        val text = messages.joinToString("\n\n") { m ->
            when (m.role) {
                "user" -> "${tr("我")}：${m.content}"
                "tool" -> m.content
                else -> "${tr("AI")}：${m.content}"
            }
        }
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        Session.toast(tr("对话已复制"))
    }

    // 悬浮窗是 WindowManager.addView 的独立窗口（非 Activity），Material3 AlertDialog
    // 在这里创建系统 Dialog 会因缺少 Activity token 崩溃。因此所有弹窗改为内联覆盖层。
    Box(modifier = Modifier.fillMaxSize()) {
    // imePadding：软键盘弹起时整个内容区上移，输入框不会被键盘盖住
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Rose.Surface.copy(alpha = bgOpacity))
            .imePadding()
    ) {
        // ===== 顶部状态条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(GgIconSize.Lg)
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr("AI 对话"),
                    color = Rose.Text,
                    fontSize = fsp(13f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (model.isNotBlank()) model else tr("未配置模型"),
                    color = Rose.TextSecond,
                    fontSize = fsp(10f),
                    maxLines = 1
                )
                // token 计量条：本轮 / 会话累计 / 上下文估算，点击查看详细
                val u = AiChatEngine.lastUsage
                Text(
                    text = buildString {
                        append(tr("会话")).append(' ').append(formatTokens(AiChatEngine.sessionUsage.totalTokens))
                        if (u != null) append("  ·  ").append(tr("本轮")).append(' ').append(formatTokens(u.totalTokens))
                        if (AiChatEngine.contextEstimate > 0) {
                            append("  ·  ").append(tr("上下文")).append(' ').append(formatTokens(AiChatEngine.contextEstimate))
                        }
                    },
                    color = Rose.Primary,
                    fontSize = fsp(9f),
                    maxLines = 1,
                    modifier = Modifier.clickable { showUsage = !showUsage }
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Card.copy(alpha = cardAlpha))
                    .clickable { showSessions = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tr("会话"),
                    color = Rose.Primary,
                    fontSize = fsp(9f),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Card.copy(alpha = cardAlpha))
                    .clickable { copyAll() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = tr("复制对话"),
                    tint = Rose.Primary,
                    modifier = Modifier.size(GgIconSize.Md)
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Card.copy(alpha = cardAlpha))
                    .clickable { showConfig = !showConfig; persistConfig() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (showConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = tr("配置"),
                    tint = Rose.Primary,
                    modifier = Modifier.size(GgIconSize.Md)
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Rose.Card.copy(alpha = cardAlpha))
                    .clickable { clearChat() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = tr("清空对话"),
                    tint = Rose.TextSecond,
                    modifier = Modifier.size(GgIconSize.Md)
                )
            }
        }

        // ===== 可折叠配置区 =====
        AnimatedVisibility(visible = showConfig) {
            // 滑动修复：配置内容较长，限高后自身可垂直滚动，避免展开时把消息列表挤没且无法滑动
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Rose.Card.copy(alpha = cardAlpha))
                    .heightIn(max = if (isLandscapeAi) 300.dp else 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(tr("服务地址"), color = Rose.Primary, fontSize = fsp(11f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                GgTextField(
                    value = TextFieldValue(baseUrl, TextRange(baseUrl.length)),
                    onValueChange = { baseUrl = it.text },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://api.example.com 或 192.168.1.10:8080"
                )
                Spacer(Modifier.height(8.dp))
                Text(tr("API Key（可选）"), color = Rose.Primary, fontSize = fsp(11f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                GgTextField(
                    value = TextFieldValue(apiKey, TextRange(apiKey.length)),
                    onValueChange = { apiKey = it.text },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "sk-..."
                )
                Spacer(Modifier.height(8.dp))
                Text(tr("模型"), color = Rose.Primary, fontSize = fsp(11f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                GgTextField(
                    value = TextFieldValue(model, TextRange(model.length)),
                    onValueChange = { model = it.text; showModelPicker = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "gpt-4o-mini / deepseek-v4-flash ..."
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoseButton(
                        label = if (isFetching) tr("获取中") else tr("自动获取模型"),
                        enabled = !isFetching && baseUrl.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { fetchModels() }
                    RoseButton(
                        label = tr("保存配置"),
                        modifier = Modifier.weight(1f)
                    ) {
                        persistConfig()
                        Session.toast(tr("配置已保存"))
                    }
                }
                // 全自动执行开关（开启后不再逐次确认任何工具操作）
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (autoExecute) Rose.Primary.copy(alpha = 0.18f) else Rose.CardHigh.copy(alpha = cardAlpha))
                        .clickable {
                            autoExecute = !autoExecute
                            Session.setSetting("ai_auto_execute", if (autoExecute) "1" else "0")
                            Session.toast(tr(if (autoExecute) "已开启全自动执行" else "已关闭全自动执行，写操作将逐次确认"))
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("全自动执行（默认同意全部，不再逐次确认）"),
                        color = if (autoExecute) Rose.Primary else Rose.TextSecond,
                        fontSize = fsp(11f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (autoExecute) tr("开") else tr("关"),
                        color = if (autoExecute) Rose.Primary else Rose.TextSecond,
                        fontSize = fsp(11f),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr("「结果」按钮：开启后发送消息会自动附带当前搜索结果前 10 条，让 AI 基于真实数据分析。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(10f)
                )

                // ===== 上下文 / token / 性能设置 =====
                Spacer(Modifier.height(8.dp))
                Text(tr("上下文与性能"), color = Rose.Primary, fontSize = fsp(12f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                ToggleRow(tr("流式输出（边生成边显示）"), streamEnabled, cardAlpha) {
                    streamEnabled = !streamEnabled
                    Session.setSetting("ai_stream", if (streamEnabled) "1" else "0")
                    Session.toast(tr(if (streamEnabled) "已开启流式输出" else "已关闭流式输出"))
                }
                ToggleRow(tr("按需工具（先发现再调用，省 token）"), onDemandTools, cardAlpha) {
                    onDemandTools = !onDemandTools
                    Session.setSetting("ai_tools_on_demand", if (onDemandTools) "1" else "0")
                    Session.toast(tr(if (onDemandTools) "已开启按需工具" else "已开启全量工具"))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(tr("保留轮数"), Session.setting("ai_keep_recent")?.toIntOrNull()?.toString().orEmpty(), cardAlpha,
                        { v -> Session.setSetting("ai_keep_recent", v.coerceIn(2, 20).toString()) }, Modifier.weight(1f))
                    NumberField(tr("每轮上限"), Session.setting("ai_max_rounds")?.toIntOrNull()?.toString().orEmpty(), cardAlpha,
                        { v -> Session.setSetting("ai_max_rounds", v.coerceIn(4, 40).toString()) }, Modifier.weight(1f))
                    NumberField(tr("maxTokens"), Session.setting("ai_max_tokens")?.toIntOrNull()?.toString().orEmpty(), cardAlpha,
                        { v -> Session.setSetting("ai_max_tokens", v.coerceIn(256, 8192).toString()) }, Modifier.weight(1f))
                }
                Text(
                    text = tr("保留轮数：更早历史压成摘要；每轮上限：最多自动迭代次数；maxTokens：单次回复预算。"),
                    color = Rose.TextSecond,
                    fontSize = fsp(9f)
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { AiChatEngine.resetTotalUsage(); Session.toast(tr("累计计量已重置")) }) {
                    Text(tr("重置累计 token"), color = Rose.Primary, fontSize = fsp(10f))
                }
                AnimatedVisibility(visible = showModelPicker && models.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn {
                            items(models) { m ->
                                Text(
                                    text = "• $m",
                                    color = if (m == model) Rose.Primary else Rose.Text,
                                    fontSize = fsp(11f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = m
                                            persistConfig()
                                            showModelPicker = false
                                        }
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (error != null) {
            Text(
                text = error.orEmpty(),
                color = Rose.Red,
                fontSize = fsp(10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable { AiChatEngine.dismissError() }
            )
        }

        // ===== 消息列表 =====
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = tr("输入消息开始对话。先在上方配置 AI 服务。"),
                                color = Rose.TextSecond,
                                fontSize = fsp(11f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = tr("AI 可调用工具直接操作 GGNext 功能"),
                                color = Rose.Primary,
                                fontSize = fsp(10f)
                            )
                        }
                    }
                }
            }
            itemsIndexed(messages) { index, msg ->
                val isUser = msg.role == "user"
                val isTool = msg.role == "tool"
                if (isTool) {
                    // 工具调用：默认折叠成一行，点击展开看完整结果
                    val info = remember(msg.content) { parseToolMessage(msg.content) }
                    val expanded = expandedTools.contains(index)
                    ToolCallCard(
                        info = info,
                        expanded = expanded,
                        cardAlpha = cardAlpha,
                        onToggle = {
                            expandedTools = if (expanded) expandedTools - index else expandedTools + index
                        },
                        onCopy = { clipboard.setText(AnnotatedString(msg.content)); Session.toast(tr("工具结果已复制")) }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Bubble(
                            text = msg.content,
                            isUser = isUser,
                            isTool = false,
                            cardAlpha = cardAlpha,
                            modifier = Modifier
                                .widthIn(max = bubbleMaxWidth)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 12.dp,
                                        topEnd = 12.dp,
                                        bottomStart = if (isUser) 12.dp else 2.dp,
                                        bottomEnd = if (isUser) 2.dp else 12.dp
                                    )
                                )
                                .background(
                                    if (isUser) Rose.Primary else Rose.Card.copy(alpha = cardAlpha)
                                )
                                .clickable { clipboard.setText(AnnotatedString(msg.content)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            if (sending) {
                item {
                    Text(
                        text = if (currentTool != null) tr("执行工具：") + currentTool else tr("思考中…"),
                        color = Rose.Primary,
                        fontSize = fsp(11f),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        // ===== 实时对话推荐（随输入内容 / GGNext 状态 / 最近 AI 回复变化）=====
        val suggestions = buildChatSuggestions(ui, messages, input.text)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(GgIconSize.Sm)
            )
            Spacer(Modifier.width(6.dp))
            suggestions.forEach { prompt ->
                Text(
                    text = tr(prompt),
                    color = Rose.Primary,
                    fontSize = fsp(10f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Rose.Card.copy(alpha = cardAlpha))
                        .clickable(enabled = !sending) {
                            input = TextFieldValue(prompt, TextRange(prompt.length))
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        // ===== 输入区 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Rose.CardHigh.copy(alpha = cardAlpha))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                GgTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp, max = 110.dp),
                    placeholder = tr("输入消息…"),
                    suppressSystemKeyboard = false
                )
            }
            Spacer(Modifier.width(6.dp))
            // 附加当前搜索结果开关（全局持久，切 tab 不丢）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (attachResults) Rose.Primary else Rose.CardHigh.copy(alpha = cardAlpha))
                    .clickable {
                        AiChatEngine.attachResults = !AiChatEngine.attachResults
                        Session.toast(
                            if (AiChatEngine.attachResults) {
                                tr("已开启：发送时附带当前搜索结果前 10 条")
                            } else {
                                tr("已关闭：发送时不附带搜索结果")
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "结果",
                    color = if (attachResults) Color.White else Rose.TextSecond,
                    fontSize = fsp(10f)
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (sending || input.text.isBlank()) Rose.CardHigh.copy(alpha = cardAlpha) else Rose.Primary)
                    .clickable(enabled = !sending && input.text.isNotBlank()) { send() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = tr("发送"),
                    tint = if (sending || input.text.isBlank()) Rose.TextSecond else Color.White,
                    modifier = Modifier.size(GgIconSize.Lg)
                )
            }
        }
    } // 关闭内容 Column —— 以下弹窗位于 Box 顶层，遮罩可覆盖整页

        // ===== 写操作确认弹窗（来自全局引擎，切 tab 后回来仍在）——内联覆盖层，避免悬浮窗 token 闪退 =====
        pendingTool?.let { p ->
            InlineDialog(
                onDismiss = { AiChatEngine.approve(false) },
                title = tr("确认执行操作"),
                cardAlpha = cardAlpha,
                body = {
                    Text(tr("AI 请求执行以下操作，该操作会修改目标进程："), color = Rose.Text, fontSize = fsp(12f))
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, color = Rose.Primary, fontSize = fsp(13f), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(p.arguments.take(400), color = Rose.TextSecond, fontSize = fsp(10f))
                },
                confirmLabel = tr("确认执行"), onConfirm = { AiChatEngine.approve(true) },
                dismissLabel = tr("取消"), onDismissButton = { AiChatEngine.approve(false) }
            )
        }

        // ===== 会话管理弹窗 =====
        // 闪退修复：Dialog 组合期间直接遍历 SnapshotStateList，并在点击回调里修改它
        // （switchSession/deleteSession/newSession 会增删列表元素与替换 messages），
        // 容易触发 ConcurrentModificationException / 重组期写状态。改为：
        // 1) 遍历快照；2) 先关闭弹窗、下一帧再执行变更；3) 全程 try-catch 并记录日志。
        if (showSessions) {
            val sessionSnapshot = remember { AiChatEngine.sessions.toList() }
            InlineDialog(
                onDismiss = { showSessions = false },
                title = tr("会话管理"),
                cardAlpha = cardAlpha,
                body = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (sessionSnapshot.isEmpty()) {
                            Text(tr("暂无会话"), color = Rose.TextSecond, fontSize = fsp(11f))
                        }
                        sessionSnapshot.forEach { s ->
                            val isCurrent = s.id == AiChatEngine.currentSessionId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isCurrent) Rose.Primary.copy(alpha = 0.16f)
                                        else Rose.CardHigh.copy(alpha = cardAlpha)
                                    )
                                    .clickable(enabled = !sending) {
                                        showSessions = false
                                        scope.launch {
                                            runCatching {
                                                AiChatEngine.switchSession(s.id)
                                                Session.toast(tr("已切换会话"))
                                            }.onFailure { t ->
                                                com.littlecodesauce.ggnext.data.AppLog.e("AI", "切换会话异常", t)
                                                Session.toast(tr("切换会话失败") + ": ${t.message}")
                                            }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isCurrent) "• ${s.title}" else s.title,
                                        color = if (isCurrent) Rose.Primary else Rose.Text,
                                        fontSize = fsp(11f), fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${formatTokens(s.promptTokens.toInt() + s.completionTokens.toInt())} tokens",
                                        color = Rose.TextSecond, fontSize = fsp(9f)
                                    )
                                }
                                if (sessionSnapshot.size > 1) {
                                    Text(
                                        tr("删除"), color = Rose.Red, fontSize = fsp(10f),
                                        modifier = Modifier.clickable(enabled = !sending) {
                                            showSessions = false
                                            scope.launch {
                                                runCatching {
                                                    AiChatEngine.deleteSession(s.id)
                                                    Session.toast(tr("会话已删除"))
                                                }.onFailure { t ->
                                                    com.littlecodesauce.ggnext.data.AppLog.e("AI", "删除会话异常", t)
                                                    Session.toast(tr("删除会话失败") + ": ${t.message}")
                                                }
                                            }
                                        }.padding(4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                },
                confirmLabel = tr("新建会话"), onConfirm = {
                    showSessions = false
                    scope.launch {
                        runCatching {
                            AiChatEngine.newSession()
                            Session.toast(tr("已新建会话"))
                        }.onFailure { t ->
                            com.littlecodesauce.ggnext.data.AppLog.e("AI", "新建会话异常", t)
                            Session.toast(tr("新建会话失败") + ": ${t.message}")
                        }
                    }
                },
                dismissLabel = tr("关闭"), onDismissButton = { showSessions = false }
            )
        }

        // ===== token 详细弹窗 =====
        if (showUsage) {
            InlineDialog(
                onDismiss = { showUsage = false },
                title = tr("Token 用量"),
                cardAlpha = cardAlpha,
                body = {
                    Column {
                        val u = AiChatEngine.lastUsage
                        if (u != null) {
                            UsageLine(tr("本轮输入"), u.promptTokens)
                            UsageLine(tr("本轮输出"), u.completionTokens)
                        }
                        UsageLine(tr("会话累计输入"), AiChatEngine.sessionUsage.promptTokens)
                        UsageLine(tr("会话累计输出"), AiChatEngine.sessionUsage.completionTokens)
                        UsageLine(tr("全局累计"), AiChatEngine.totalUsage.totalTokens)
                        UsageLine(tr("本轮上下文估算"), AiChatEngine.contextEstimate)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("服务端返回 usage 时精确计量，否则按中英文字符估算。上下文估算为发送前预算。"),
                            color = Rose.TextSecond, fontSize = fsp(9f)
                        )
                    }
                },
                confirmLabel = tr("关闭"), onConfirm = { showUsage = false }
            )
        }
    } // 关闭 Box
}

/**
 * 内联对话框：悬浮窗是独立 Window（非 Activity），Material3 AlertDialog 会因缺少
 * Activity token 崩溃。这里用纯 Compose 的遮罩 + 卡片实现，行为一致且无 token 依赖。
 */
@Composable
private fun InlineDialog(
    onDismiss: () -> Unit,
    title: String,
    cardAlpha: Float,
    body: @Composable ColumnScope.() -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismissButton: (() -> Unit)? = null
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Rose.Card.copy(alpha = cardAlpha.coerceIn(0.85f, 1f)))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { /* 消费点击防穿透 */ }
                .padding(16.dp)
        ) {
            Text(title, color = Rose.Text, fontSize = fsp(15f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Column(content = body)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End) {
                if (dismissLabel != null && onDismissButton != null) {
                    TextButton(onClick = onDismissButton) {
                        Text(dismissLabel, color = Rose.TextSecond)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (confirmLabel != null && onConfirm != null) {
                    TextButton(onClick = onConfirm) {
                        Text(confirmLabel, color = Rose.Primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLine(label: String, tokens: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Rose.TextSecond, fontSize = fsp(11f), modifier = Modifier.weight(1f))
        Text(formatTokens(tokens), color = Rose.Primary, fontSize = fsp(11f), fontWeight = FontWeight.Bold)
    }
}

private fun formatTokens(n: Int): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000f)
    else -> n.toString()
}

@Composable
private fun ToggleRow(label: String, enabled: Boolean, cardAlpha: Float, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Rose.Primary.copy(alpha = 0.14f) else Rose.CardHigh.copy(alpha = cardAlpha))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) Rose.Primary else Rose.TextSecond,
            fontSize = fsp(10f), modifier = Modifier.weight(1f))
        Text(if (enabled) tr("开") else tr("关"),
            color = if (enabled) Rose.Primary else Rose.TextSecond,
            fontSize = fsp(10f), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NumberField(label: String, value: String, cardAlpha: Float, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(value.ifBlank { "0" }) }
    Column(modifier.fillMaxWidth()) {
        Text(label, color = Rose.TextSecond, fontSize = fsp(9f))
        GgTextField(
            value = TextFieldValue(text, TextRange(text.length)),
            onValueChange = { text = it.text },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "0"
        )
        TextButton(onClick = { onCommit(text.toIntOrNull() ?: 0) }) {
            Text(tr("应用"), color = Rose.Primary, fontSize = fsp(9f))
        }
    }
}

@Composable
private fun Bubble(
    text: String,
    isUser: Boolean,
    isTool: Boolean,
    cardAlpha: Float,
    modifier: Modifier = Modifier
) {
    val baseColor = when {
        isUser -> Color.White
        isTool -> Rose.TextSecond
        else -> Rose.Text
    }
    val accent = if (isUser) Color.White else Rose.Primary
    val secondary = if (isUser) Color.White.copy(alpha = 0.82f) else Rose.TextSecond
    MarkdownText(
        text = text,
        color = baseColor,
        accent = accent,
        secondary = secondary,
        fontSize = fsp(12f),
        modifier = modifier
    )
}

/** 工具调用卡片：默认折叠成标题 + 摘要一行，点击展开看完整结果，避免长 JSON 刷屏。 */
@Composable
private fun ToolCallCard(
    info: ToolInfo,
    expanded: Boolean,
    cardAlpha: Float,
    onToggle: () -> Unit,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Rose.CardHigh.copy(alpha = cardAlpha))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                tint = Rose.Primary,
                modifier = Modifier.size(GgIconSize.Sm)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = info.name,
                color = Rose.Primary,
                fontSize = fsp(11f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!expanded && info.summary.isNotBlank()) {
                Text(
                    text = info.summary,
                    color = Rose.TextSecond,
                    fontSize = fsp(9f),
                    maxLines = 1,
                    modifier = Modifier.weight(1.4f)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                tint = Rose.TextSecond,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = info.body,
                    color = Rose.TextSecond,
                    fontSize = fsp(10f),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Rose.Card.copy(alpha = cardAlpha), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
                TextButton(onClick = onCopy) {
                    Text(tr("复制结果"), color = Rose.Primary, fontSize = fsp(10f))
                }
            }
        }
    }
}
