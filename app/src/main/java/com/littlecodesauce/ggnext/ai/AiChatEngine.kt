package com.littlecodesauce.ggnext.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.AppLog
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.mcp.McpToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 会影响目标进程状态或内存内容的工具：全自动关闭时需用户逐次确认。 */
private val WRITE_TOOLS = setOf(
    "attach_process", "write_memory", "patch_memory", "freeze_value",
    "allocate_memory_page", "bp_modify_register", "manage_breakpoints",
    "process_control", "manage_search_results", "manage_saved_results"
)

/** 最多自主迭代轮数：给模型足够空间完成搜索 → 分析 → 读写 → 验证的完整链路。 */
private const val MAX_ROUNDS = 20

/** 虚拟「任务完成」工具：模型主动调用它表示用户请求已完成，引擎据此收尾。 */
private const val TASK_DONE_TOOL = "task_complete"

/** 虚拟「按需工具发现」工具：返回工具清单，模型再选定具体工具（P2）。 */
private const val FIND_TOOL = "find_tools"

/** 系统指令：强制使用原生工具调用、不得提前结束、完成后必须调用 task_complete。 */
private const val AGENT_SYSTEM_PROMPT = """你是 GGNext 的 AI 操作助手，可以通过原生 Function Calling 调用工具直接操作 GGNext（内存搜索/读写、进程控制、断点、汇编、MCP 工具等）。

工作规则：
1. 必须使用工具执行用户的请求，不要只用文字描述操作过程。
2. 每次调用工具后，根据返回结果决定下一步；出错时修正参数重试，同一工具最多重试 2 次。
3. 任务全部完成后，必须调用 task_complete 工具并给出简洁总结；只有确认完成时才调用它。
4. 不要提前停止：只要任务未完成且未达上限，就继续调用工具推进。
5. 若确实无法完成（缺少权限、目标不存在等），也调用 task_complete 并说明失败原因。
6. 需要更多工具时先调用 find_tools 查询工具名称，再调用具体工具。
7. 回复使用标准 Markdown，会被界面渲染为标题、列表、表格与代码块。"""

/** 待确认的工具调用；deferred 把弹窗选择结果传回协程。 */
data class PendingTool(
    val name: String,
    val arguments: String,
    val deferred: CompletableDeferred<Boolean>
)

/** 一个会话的轻量元数据（消息存于 messages 快照列表，由 currentSessionId 决定归属）。 */
data class ChatSessionMeta(
    val id: String,
    var title: String,
    val createdAt: Long,
    var promptTokens: Long = 0,
    var completionTokens: Long = 0
)

/**
 * 全局 AI 对话引擎（v0.9.4）。
 *
 * 进程级 SupervisorJob 作用域，切 tab、关悬浮窗、息屏都不影响进行中的请求。
 * v0.9.4 新增：上下文窗口管理（早期历史压摘要）、工具结果结构化摘要、token 计量、
 * 流式输出、多会话、指数退避重试与按需工具发现。
 */
object AiChatEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** UI 直接观察的对话消息（唯一真相源，属于当前会话）。 */
    val messages = mutableStateListOf<AiClient.ChatMessage>()

    /** 全部会话元数据；消息体按会话 id 存于 sessionMessages。 */
    val sessions = mutableStateListOf<ChatSessionMeta>()
    var currentSessionId by mutableStateOf("")
        private set

    private val sessionMessages = LinkedHashMap<String, MutableList<AiClient.ChatMessage>>()

    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var currentTool by mutableStateOf<String?>(null)
        private set
    var pendingTool by mutableStateOf<PendingTool?>(null)
        private set
    var attachResults by mutableStateOf(true)

    /** 流式输出：正在生成的文本增量（UI 可实时渲染）。 */
    var streamingText by mutableStateOf("")
        private set

    /** token 计量：本轮 / 当前会话累计 / 全局累计。 */
    var lastUsage by mutableStateOf<AiClient.TokenUsage?>(null)
        private set
    var sessionUsage by mutableStateOf(AiClient.TokenUsage())
        private set
    var totalUsage by mutableStateOf(AiClient.TokenUsage())
        private set
    /** 发送前估算的上下文 token（预算提示）。 */
    var contextEstimate by mutableStateOf(0)
        private set

    /** OpenAI 工具定义由 MCP 注册表转换而来，只构造一次。 */
    private val fullTools by lazy {
        val arr = AiClient.mcpToOpenAiTools(McpToolExecutor.toolDefinitions())
        arr.put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", TASK_DONE_TOOL)
                .put("description", "任务全部完成或确认无法完成时调用，并给出简洁总结。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("summary", JSONObject()
                        .put("type", "string")
                        .put("description", "任务结果总结")))
                    .put("required", JSONArray().put("summary")))))
        arr
    }

    /** 按需模式下已激活的真实工具名集合（find_tools 查询后注入）。 */
    private val activeToolNames = LinkedHashSet<String>()
    private val coreToolNames = setOf(
        "get_status", "list_processes", "attach_process", "process_control"
    )

    /** 工具发现元工具定义。 */
    private val findToolDef by lazy {
        JSONArray().put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", FIND_TOOL)
                .put("description", "查询可用于 GGNext 操作的工具清单。当用户请求涉及未列出的能力（内存读写、断点、汇编/反汇编、搜索、保存列表等）时，先用本工具按关键词查询。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("query", JSONObject()
                        .put("type", "string")
                        .put("description", "工具关键词，如 搜索/写入/断点/汇编 等")))
                    .put("required", JSONArray().put("query")))))
    }

    init {
        val (active, stored) = AiChatStore.loadSessions()
        for (s in stored) {
            sessions.add(ChatSessionMeta(s.id, s.title, s.createdAt, s.promptTokens, s.completionTokens))
            sessionMessages[s.id] = s.messages.toMutableList()
        }
        currentSessionId = active.ifBlank { stored.firstOrNull()?.id ?: newSessionId() }
        if (sessions.none { it.id == currentSessionId }) {
            sessions.add(ChatSessionMeta(currentSessionId, "默认会话", System.currentTimeMillis()))
            sessionMessages[currentSessionId] = mutableListOf()
        }
        messages.addAll(sessionMessages[currentSessionId].orEmpty())
        persist()
    }

    // ---- 配置读取（实时，切 tab 不丢）----
    fun autoExecute(): Boolean = Session.setting("ai_auto_execute") != "0"
    fun streamEnabled(): Boolean = Session.setting("ai_stream") != "0"
    fun onDemandTools(): Boolean = Session.setting("ai_tools_on_demand") == "1"
    private fun keepRecent(): Int = (Session.setting("ai_keep_recent")?.toIntOrNull() ?: 8).coerceIn(2, 20)
    private fun maxTokens(): Int = (Session.setting("ai_max_tokens")?.toIntOrNull() ?: 1536).coerceIn(256, 8192)
    private fun maxRounds(): Int = (Session.setting("ai_max_rounds")?.toIntOrNull() ?: MAX_ROUNDS).coerceIn(4, 40)

    fun dismissError() { error = null }

    fun approve(approved: Boolean) {
        val p = pendingTool ?: return
        pendingTool = null
        p.deferred.complete(approved)
        AppLog.i("AI", "工具确认 ${p.name} -> $approved")
    }

    // ---- 会话管理 ----
    private fun newSessionId() = "s" + System.currentTimeMillis().toString(36)

    fun newSession() {
        if (messages.isNotEmpty()) persistCurrentMessages()
        currentSessionId = newSessionId()
        val meta = ChatSessionMeta(currentSessionId, "新会话 " + sessions.size, System.currentTimeMillis())
        sessions.add(meta)
        sessionMessages[currentSessionId] = mutableListOf()
        messages.clear()
        clearRuntime()
        persist()
        AppLog.i("AI", "新建会话 $currentSessionId")
    }

    fun switchSession(id: String) {
        if (id == currentSessionId || sessions.none { it.id == id }) return
        persistCurrentMessages()
        currentSessionId = id
        messages.clear()
        messages.addAll(sessionMessages[id].orEmpty())
        // 恢复该会话的 usage
        sessions.firstOrNull { it.id == id }?.let {
            sessionUsage = AiClient.TokenUsage(it.promptTokens.toInt(), it.completionTokens.toInt(),
                (it.promptTokens + it.completionTokens).toInt())
        }
        clearRuntime()
        persist()
        AppLog.i("AI", "切换会话 $id，消息 ${messages.size}")
    }

    fun deleteSession(id: String) {
        if (sessions.size <= 1) return
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        sessions.removeAt(idx)
        sessionMessages.remove(id)
        if (id == currentSessionId) {
            currentSessionId = sessions[0].id
            messages.clear()
            messages.addAll(sessionMessages[currentSessionId].orEmpty())
            clearRuntime()
        }
        persist()
        AppLog.i("AI", "删除会话 $id")
    }

    fun renameSession(id: String, title: String) {
        sessions.firstOrNull { it.id == id }?.title = title.take(40)
        persist()
    }

    private fun clearRuntime() {
        error = null
        currentTool = null
        pendingTool = null
        streamingText = ""
        lastUsage = null
        contextEstimate = 0
    }

    private fun persistCurrentMessages() {
        sessionMessages[currentSessionId] = messages.toMutableList()
    }

    private fun persist() {
        persistCurrentMessages()
        sessions.firstOrNull { it.id == currentSessionId }?.let {
            it.promptTokens = sessionUsage.promptTokens.toLong()
            it.completionTokens = sessionUsage.completionTokens.toLong()
        }
        val stored = sessions.map { meta ->
            AiChatStore.StoredSession(
                meta.id, meta.title,
                sessionMessages[meta.id].orEmpty(),
                meta.promptTokens, meta.completionTokens, meta.createdAt
            )
        }
        AiChatStore.saveSessions(currentSessionId, stored)
    }

    /** 发起一次对话。 */
    fun send(appContext: Context, promptRaw: String, includeResults: Boolean) {
        val prompt = promptRaw.trim()
        if (prompt.isEmpty() || sending) return

        val baseUrl = Session.setting("ai_base_url")
        val apiKey = Session.setting("ai_api_key")
        val model = Session.setting("ai_model")
        if (baseUrl.isBlank() || model.isBlank()) {
            error = "请先配置服务地址与模型"
            return
        }

        var text = prompt
        if (includeResults) {
            val ui = Session.ui.value
            if (ui.results.isNotEmpty()) {
                val sample = ui.results.take(10).joinToString("\n") { item ->
                    val addr = "0x" + java.lang.Long.toHexString(item.address)
                    "$addr  ${Formatter.primary(item, ui.valueMask)}  ${item.region}"
                }
                text = "$prompt\n\n[当前内存搜索结果（前 ${minOf(10, ui.results.size)} / ${ui.results.size} 条）]\n$sample"
            }
        }

        error = null
        sending = true
        streamingText = ""
        append(AiClient.ChatMessage("user", prompt))

        // 会话标题：首条用户消息
        sessions.firstOrNull { it.id == currentSessionId }?.let {
            if (it.title.startsWith("新会话") || it.title == "默认会话") it.title = prompt.take(24)
        }

        AppLog.i("AI", "send model=$model base=$baseUrl tools=${buildTools().length()} " +
            "auto=${autoExecute()} stream=${streamEnabled()} onDemand=${onDemandTools()}")

        scope.launch {
            val working = buildWorkingMessages(text)
            contextEstimate = AiClient.estimateRequest(working, buildTools())
            AppLog.i("AI", "上下文估算 $contextEstimate tokens，消息 ${working.size} 条")
            val roundsLimit = maxRounds()
            var rounds = 0
            var idleText = 0
            var finished = false
            try {
                while (rounds < roundsLimit) {
                    rounds++
                    val r = AiClient.retryable(attempts = 3) {
                        val resp = if (streamEnabled()) {
                            streamingText = ""
                            AiClient.chatWithToolsStream(
                                baseRaw = baseUrl, apiKey = apiKey, model = model,
                                messages = working.toList(), tools = buildTools(),
                                onToken = { chunk ->
                                    streamingText += chunk
                                    appendStreamingChunk(chunk)
                                },
                                maxTokens = maxTokens()
                            ).let { AiClient.Result(true, it) }
                        } else {
                            AiClient.chatWithTools(
                                baseRaw = baseUrl, apiKey = apiKey, model = model,
                                messages = working.toList(), tools = buildTools(),
                                maxTokens = maxTokens()
                            )
                        }
                        resp
                    }
                    if (!r.ok) {
                        error = r.error
                        AppLog.e("AI", "round $rounds 请求失败：${r.error}")
                        finished = true
                        break
                    }
                    val resp = r.data
                    if (resp == null) { finished = true; break }

                    // 记录 usage（优先服务端，缺失则本地估算）
                    val usage = resp.usage ?: AiClient.TokenUsage(
                        promptTokens = AiClient.estimateRequest(working, buildTools()),
                        completionTokens = AiClient.estimateTokens(resp.content)
                    )
                    recordUsage(usage)
                    streamingText = ""

                    val assistantMsg = AiClient.ChatMessage(
                        role = "assistant", content = resp.content,
                        toolCalls = resp.toolCalls.ifEmpty { null }
                    )
                    working.add(assistantMsg)

                    if (resp.toolCalls.isEmpty()) {
                        // 流式模式下 UI 已通过 appendStreamingChunk 逐块写入，无需重复 append
                        if (resp.content.isNotBlank() && !streamEnabled()) append(assistantMsg)
                        // 流式结束：把最后一条流式消息补全为最终内容（去除可能的分片差异）
                        if (resp.content.isNotBlank() && streamEnabled()) {
                            val last = messages.lastOrNull()
                            if (last != null && last.role == "assistant" && last.content != resp.content) {
                                messages[messages.lastIndex] = last.copy(content = resp.content)
                                persist()
                            }
                        }
                        idleText++
                        AppLog.i("AI", "round $rounds 纯文本 ${resp.content.length} 字 idle=$idleText")
                        if (idleText >= 2) { finished = true; break }
                        working.add(AiClient.ChatMessage("user",
                            "请继续使用工具执行任务，不要只输出文字。完成后调用 $TASK_DONE_TOOL 并总结；无法完成也调用它说明原因。"))
                        continue
                    }
                    idleText = 0

                    var taskDone = false
                    for (tc in resp.toolCalls) {
                        // 虚拟完成工具
                        if (tc.name == TASK_DONE_TOOL) {
                            val summary = runCatching { JSONObject(tc.arguments).optString("summary", "") }
                                .getOrDefault("")
                            val finalText = listOf(resp.content, summary)
                                .filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "任务已结束。" }
                            append(AiClient.ChatMessage("assistant", finalText))
                            AppLog.i("AI", "round $rounds 收到 task_complete：${finalText.take(200)}")
                            taskDone = true
                            finished = true
                            break
                        }
                        // 虚拟工具发现：返回匹配工具清单并激活其 schema
                        if (tc.name == FIND_TOOL) {
                            val query = runCatching { JSONObject(tc.arguments).optString("query", "") }
                                .getOrDefault("")
                            val found = discoverTools(query)
                            found.forEach { activeToolNames.add(it) }
                            val toolMsg = AiClient.ChatMessage("tool",
                                "[工具发现] 关键词「$query」可用工具：${found.joinToString(", ")}", toolCallId = tc.id)
                            working.add(toolMsg)
                            append(toolMsg)
                            AppLog.i("AI", "find_tools $query -> ${found.size} 项")
                            continue
                        }
                        currentTool = tc.name
                        var approved = true
                        if (tc.name in WRITE_TOOLS && !autoExecute()) {
                            val deferred = CompletableDeferred<Boolean>()
                            pendingTool = PendingTool(tc.name, tc.arguments, deferred)
                            approved = deferred.await()
                            pendingTool = null
                        }
                        val resultText = if (!approved) {
                            "用户拒绝执行该操作"
                        } else {
                            withContext(Dispatchers.IO) {
                                try {
                                    val args = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                                    AppLog.i("AI", "调用工具 ${tc.name} args=${tc.arguments.take(300)}")
                                    val res = McpToolExecutor.executeTool(appContext.applicationContext, tc.name, args)
                                    val out = (if (res.isError) "错误：" else "") + res.text
                                    AppLog.i("AI", "工具 ${tc.name} 返回 ${out.length} 字 isError=${res.isError}")
                                    out
                                } catch (e: Throwable) {
                                    AppLog.e("AI", "工具 ${tc.name} 执行异常", e)
                                    "工具执行失败：${e.message}"
                                }
                            }
                        }
                        currentTool = null
                        // UI 展示原文（截断 4000，带工具名便于折叠标题），发给模型的是结构化摘要
                        val displayText = resultText.take(4000)
                        val toolMsg = AiClient.ChatMessage("tool", "[工具] ${tc.name}：$displayText", toolCallId = tc.id)
                        working.add(AiClient.ChatMessage("tool",
                            "[工具] ${tc.name}：${summarizeToolResult(tc.name, resultText)}", toolCallId = tc.id))
                        append(toolMsg)
                    }
                    if (taskDone) break
                }
                if (!finished) {
                    val last = working.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
                    val tail = buildString {
                        append("已执行 $rounds 轮，达到 $roundsLimit 轮上限。")
                        if (!last.isNullOrBlank()) append("\n\n当前进展：\n$last")
                    }
                    append(AiClient.ChatMessage("assistant", tail))
                    AppLog.i("AI", "达到 $roundsLimit 轮上限，强制收尾")
                }
            } catch (t: Throwable) {
                error = "执行异常：${t.message ?: t.javaClass.simpleName}"
                AppLog.e("AI", "对话协程异常", t)
            } finally {
                sending = false
                currentTool = null
                pendingTool = null
                streamingText = ""
                persist()
            }
        }
    }

    /** 流式增量写入 UI 消息：更新最后一条流式 assistant，避免每块新增一条。 */
    private fun appendStreamingChunk(chunk: String) {
        if (chunk.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.toolCalls == null && last.toolCallId == null) {
            messages[messages.lastIndex] = last.copy(content = last.content + chunk)
        } else {
            messages.add(AiClient.ChatMessage("assistant", chunk))
        }
    }

    /** 记录 usage 到本轮/会话/全局。 */
    private fun recordUsage(u: AiClient.TokenUsage) {
        lastUsage = u
        sessionUsage = sessionUsage.plus(u)
        totalUsage = totalUsage.plus(u)
    }

    /** 按 tools 注册表发现工具名（返回 name + description 简短匹配）。 */
    private fun discoverTools(query: String): List<String> {
        val q = query.trim().lowercase()
        val out = ArrayList<String>()
        val defs = McpToolExecutor.toolDefinitions()
        for (i in 0 until defs.length()) {
            val t = defs.optJSONObject(i) ?: continue
            val name = t.optString("name", "")
            val desc = t.optString("description", "")
            if (name == TASK_DONE_TOOL || name == FIND_TOOL) continue
            if (q.isEmpty() || name.contains(q, true) || desc.contains(q, true) ||
                keywordMatch(q, "$name $desc")) out.add(name)
        }
        return out.take(12)
    }

    private fun keywordMatch(q: String, text: String): Boolean {
        val map = mapOf(
            "搜索" to listOf("search", "scan"), "写" to listOf("write", "patch"),
            "读" to listOf("read"), "断点" to listOf("breakpoint", "bp"),
            "汇编" to listOf("assemble", "asm"), "反汇编" to listOf("disasm"),
            "内存" to listOf("memory", "region"), "进程" to listOf("process"),
            "冻结" to listOf("freeze"), "保存" to listOf("saved"), "脚本" to listOf("script")
        )
        val keys = map.filter { q.contains(it.key) }.values.flatten()
        return keys.any { text.contains(it, true) }
    }

    /** 构造本轮工具列表：全量模式 or 按需（核心 + 已激活 + 元工具）。 */
    private fun buildTools(): JSONArray {
        if (!onDemandTools()) return fullTools
        val out = JSONArray()
        for (i in 0 until fullTools.length()) {
            val t = fullTools.optJSONObject(i) ?: continue
            val name = t.optJSONObject("function")?.optString("name", "").orEmpty()
            if (name == TASK_DONE_TOOL || coreToolNames.contains(name) || activeToolNames.contains(name)) {
                out.put(t)
            }
        }
        // task_complete 与 find_tools 始终带上
        for (i in 0 until fullTools.length()) {
            val t = fullTools.optJSONObject(i) ?: continue
            if (t.optJSONObject("function")?.optString("name", "") == TASK_DONE_TOOL && !containsTool(out, TASK_DONE_TOOL)) out.put(t)
        }
        for (i in 0 until findToolDef.length()) out.put(findToolDef.optJSONObject(i))
        return out
    }

    private fun containsTool(arr: JSONArray, name: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optJSONObject("function")?.optString("name", "") == name) return true
        }
        return false
    }

    /**
     * 上下文窗口管理：system + 早期历史摘要 + 最近 N 条原文 + 当前用户消息。
     * 避免每轮全量重发历史导致 token O(n²) 增长与窗口溢出。
     */
    private fun buildWorkingMessages(currentText: String): MutableList<AiClient.ChatMessage> {
        val out = ArrayList<AiClient.ChatMessage>()
        out.add(AiClient.ChatMessage("system", AGENT_SYSTEM_PROMPT))

        // 当前 messages 最后一条是刚 append 的 user；历史为它之前的部分
        val history = if (messages.isNotEmpty() && messages.last().role == "user") {
            messages.dropLast(1)
        } else {
            messages.toList()
        }
        val keep = keepRecent()
        val split = (history.size - keep).coerceAtLeast(0)
        if (split > 0) {
            val summary = compressHistory(history.take(split))
            if (summary.isNotBlank()) {
                out.add(AiClient.ChatMessage("system", "[历史摘要]\n$summary"))
            }
        }
        // 最近 keep 条：工具结果替换为结构化摘要，控制单条体积
        history.takeLast(keep).forEach { m ->
            out.add(if (m.role == "tool") {
                m.copy(content = "[工具] ${summarizeToolResult(extractToolName(m.content), m.content)}")
            } else m)
        }
        out.add(AiClient.ChatMessage("user", currentText))
        return out
    }

    private fun extractToolName(content: String): String {
        // UI 消息形如 "[工具] <name>：<text>" 或 "[工具发现] ..."
        val body = content.removePrefix("[工具] ").removePrefix("[工具发现] ")
        val idx = body.indexOf("：")
        val name = if (idx > 0) body.substring(0, idx) else body.take(30)
        return name.take(40)
    }

    private fun compressHistory(older: List<AiClient.ChatMessage>): String {
        val sb = StringBuilder()
        older.filter { it.role == "user" }.takeLast(4).forEach {
            sb.append("用户：").append(it.content.take(120)).append('\n')
        }
        val tools = older.filter { it.role == "tool" }.map { extractToolName(it.content) }
        if (tools.isNotEmpty()) {
            sb.append("已用工具：").append(tools.distinct().take(20).joinToString(", ")).append('\n')
        }
        older.filter { it.role == "assistant" && it.content.isNotBlank() }.takeLast(2).forEach {
            sb.append("结论：").append(it.content.take(200)).append('\n')
        }
        return sb.toString().take(900)
    }

    /** 工具结果结构化摘要：按工具类型抽取关键字段，替代 3000 字纯文本。 */
    private fun summarizeToolResult(name: String, raw: String): String {
        val text = raw.removePrefix("[工具] ")
        return when {
            name.contains("get_status", true) -> {
                runCatching {
                    val o = JSONObject(text.substring(text.indexOf('{')))
                    "状态: 模式=${o.optString("work_mode")} 附加=${o.optBoolean("attached")} " +
                        "pid=${o.optInt("pid")} 目标=${o.optString("target_label")} " +
                        "64位=${o.optBoolean("target_64bit")} 搜索结果=${o.optInt("search_result_total")} " +
                        "断点=${o.optInt("breakpoint_count", o.optInt("breakpoints"))}"
                }.getOrDefault(text.take(400))
            }
            name.contains("list_processes", true) -> {
                runCatching {
                    val o = JSONObject(text.substring(text.indexOf('{')))
                    val arr = o.optJSONArray("processes")
                    val rows = ArrayList<String>()
                    if (arr != null) for (i in 0 until minOf(15, arr.length())) {
                        val p = arr.optJSONObject(i) ?: continue
                        rows.add("${p.optInt("pid")}:${p.optString("label")}/${p.optString("package_name")}${if (p.optBoolean("x64")) "[64]" else ""}")
                    }
                    "进程 共${o.optInt("total_matched")}个：" + rows.joinToString("; ")
                }.getOrDefault(text.take(400))
            }
            name.contains("list_regions", true) -> {
                runCatching {
                    val o = JSONObject(text.substring(text.indexOf('{')))
                    "区域: count=${o.optInt("total", o.optInt("count"))} " +
                        "模块=${o.optJSONArray("modules")?.let { m -> (0 until minOf(10, m.length())).joinToString(",") { m.optString(it) } }.orEmpty()}"
                }.getOrDefault(text.take(400))
            }
            name.contains("search") || name.contains("read_memory") -> {
                // 搜索/读取：保留数量 + 前 8 条
                val head = text.take(300)
                val tail = text.takeLast(400)
                "$head …（已压缩）… $tail"
            }
            text.length > 600 -> text.take(400) + " …（已压缩，完整结果见运行日志）"
            else -> text
        }.take(900)
    }

    /** 统一追加消息：UI 列表 + 持久化同步。 */
    private fun append(msg: AiClient.ChatMessage) {
        messages.add(msg)
        persist()
    }

    fun clear() {
        messages.clear()
        sessionMessages[currentSessionId] = mutableListOf()
        sessions.firstOrNull { it.id == currentSessionId }?.let {
            it.promptTokens = 0
            it.completionTokens = 0
        }
        sessionUsage = AiClient.TokenUsage()
        clearRuntime()
        persist()
    }

    /** 清空全局累计（设置页调用）。 */
    fun resetTotalUsage() {
        totalUsage = AiClient.TokenUsage()
    }
}
