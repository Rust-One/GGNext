package com.littlecodesauce.ggnext.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 轻量 AI 客户端（零外部依赖，基于 HttpURLConnection）。
 * 兼容 OpenAI Chat Completions 协议：/v1/models 拉取模型列表，/v1/chat/completions 对话。
 * 所有网络请求均在 Dispatchers.IO 上执行。
 */
object AiClient {

    data class Result<T>(
        val ok: Boolean,
        val data: T? = null,
        val error: String? = null
    )

    data class ChatMessage(
        val role: String,
        val content: String,
        /** assistant 消息携带的模型工具调用请求 */
        val toolCalls: List<ToolCall>? = null,
        /** tool 消息对应的 tool_call_id */
        val toolCallId: String? = null
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    /** 服务端返回的 token 计量（若模型服务未返回则为 null，可用 estimateTokens 估算）。 */
    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    ) {
        fun plus(o: TokenUsage) = TokenUsage(
            promptTokens + o.promptTokens,
            completionTokens + o.completionTokens,
            totalTokens + o.totalTokens
        )
    }

    data class ChatResponse(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val usage: TokenUsage? = null
    )

    /** 规范化 base URL（委托给纯函数实现，便于单测）。 */
    fun normalizeBaseUrl(raw: String): String = AiUrl.normalizeBaseUrl(raw)

    /** 是否为明文 http。 */
    fun isCleartext(raw: String): Boolean = AiUrl.isCleartext(raw)

    private fun openConnection(url: String, apiKey: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 30_000
        conn.doInput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "GGNext-AI/1.0")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        val reader = BufferedReader(InputStreamReader(stream ?: return "", StandardCharsets.UTF_8))
        return reader.use { it.readText() }
    }

    /** 序列化消息，兼容 tool / tool_calls 角色字段。 */
    private fun serializeMessages(messages: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role).put("content", m.content)
            m.toolCallId?.let { o.put("tool_call_id", it) }
            val calls = m.toolCalls
            if (!calls.isNullOrEmpty()) {
                val ca = JSONArray()
                for (c in calls) {
                    ca.put(JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", c.name)
                            .put("arguments", c.arguments)))
                }
                o.put("tool_calls", ca)
            }
            arr.put(o)
        }
        return arr
    }

    /** 解析响应：同时提取文本与 tool_calls。 */
    private fun parseResponse(body: String): ChatResponse {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
        val msg = if (choices != null && choices.length() > 0) {
            choices.optJSONObject(0)?.optJSONObject("message")
        } else null
        val content = msg?.optString("content", "").orEmpty()
        val calls = ArrayList<ToolCall>()
        val arr = msg?.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val id = tc.optString("id", "")
                val name = fn.optString("name", "")
                if (id.isBlank() || name.isBlank()) continue
                calls.add(ToolCall(id, name, fn.optString("arguments", "{}")))
            }
        }
        // usage 计量：兼容 prompt_tokens/completion_tokens 与 input_tokens/output_tokens 两种命名
        val u = root.optJSONObject("usage")
        val usage = if (u != null) TokenUsage(
            promptTokens = u.optInt("prompt_tokens", u.optInt("input_tokens", 0)),
            completionTokens = u.optInt("completion_tokens", u.optInt("output_tokens", 0)),
            totalTokens = u.optInt("total_tokens", 0)
        ) else null
        return ChatResponse(content, calls, usage)
    }

    /**
     * 本地 token 粗估（不依赖服务端 usage）：CJK 按 1.5 token/字，其余按 4 字符/token。
     * 用于不返回 usage 的中转服务，以及发送前的上下文预算预估。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF ||
                c in 0x3000..0x303F || c in 0xFF00..0xFFEF
            ) cjk++ else other++
        }
        return (cjk * 1.5 + other / 4.0).toInt()
    }

    /** 估算一组消息 + 工具定义的总 token（发送前预算用）。 */
    fun estimateRequest(messages: List<ChatMessage>, tools: JSONArray?): Int {
        var total = 0
        for (m in messages) {
            total += estimateTokens(m.content) + 4 // role 等结构开销
            m.toolCalls?.forEach { total += estimateTokens(it.name) + estimateTokens(it.arguments) + 8 }
        }
        if (tools != null) total += estimateTokens(tools.toString())
        return total
    }

    /**
     * 指数退避重试：仅对网络/5xx/429 重试，4xx 参数错误立即失败（避免无谓等待）。
     */
    suspend fun <T> retryable(
        attempts: Int = 3,
        block: suspend () -> Result<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        var last: Result<T> = Result(false, error = "无响应")
        repeat(attempts) { i ->
            last = block()
            if (last.ok) return@withContext last
            val err = last.error.orEmpty()
            val retryableError = err.startsWith("HTTP 5") || err.startsWith("HTTP 429") ||
                err.contains("超时") || err.contains("timeout", ignoreCase = true) ||
                err.contains("拒绝") || err.contains("refused", ignoreCase = true) ||
                err.contains("网络") || err.contains("未知错误")
            if (!retryableError) return@withContext last
            if (i < attempts - 1) {
                kotlinx.coroutines.delay(500L * (1L shl i))
            }
        }
        last
    }

    /**
     * 将 MCP tools/list 格式（name / description / inputSchema）转换为 OpenAI
     * Chat Completions 的 tools 格式（type=function / function.parameters）。
     */
    fun mcpToOpenAiTools(mcpTools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until mcpTools.length()) {
            val t = mcpTools.optJSONObject(i) ?: continue
            val name = t.optString("name", "")
            if (name.isBlank()) continue
            out.put(JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", name)
                    .put("description", t.optString("description", ""))
                    .put("parameters", t.opt("inputSchema") ?: JSONObject())))
        }
        return out
    }

    /**
     * 从 {base}/v1/models 拉取模型列表。
     * 解析 OpenAI 标准结构 { "data": [ { "id": "..." }, ... ] }。
     */
    suspend fun fetchModels(baseRaw: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseRaw)
        if (base.isBlank()) return@withContext Result(false, error = "服务地址为空")
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("$base/v1/models", apiKey, "GET")
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return@withContext Result(false, error = "HTTP $code：${summarizeError(body)}")
            }
            val ids = LinkedHashSet<String>()
            val root = JSONObject(body)
            val data = root.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (id.isNotEmpty()) ids.add(id)
                }
            } else {
                // 兼容部分服务直接返回字符串数组 ["model-a", "model-b"]
                val arr = runCatching { JSONArray(body) }.getOrNull()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optString(i, "").trim()
                        if (v.isNotEmpty()) ids.add(v)
                    }
                }
            }
            if (ids.isEmpty()) {
                return@withContext Result(false, error = "未解析到任何模型，请检查服务是否兼容 /v1/models")
            }
            Result(true, ids.toList())
        } catch (e: javax.net.ssl.SSLException) {
            Result(false, error = "HTTPS 证书校验失败：${e.message}")
        } catch (e: java.net.UnknownHostException) {
            Result(false, error = "无法解析域名：${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, error = "连接超时，请检查地址与端口")
        } catch (e: java.net.ConnectException) {
            Result(false, error = "连接被拒绝，请检查服务是否启动及端口是否正确")
        } catch (e: Exception) {
            Result(false, error = e.message ?: "未知错误")
        } finally {
            conn?.disconnect()
        }
    }

    /** 发送一次最小对话用于连通性测试。 */
    suspend fun chat(
        baseRaw: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.0,
        maxTokens: Int = 64
    ): Result<String> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseRaw)
        if (base.isBlank()) return@withContext Result(false, error = "服务地址为空")
        if (model.isBlank()) return@withContext Result(false, error = "模型名为空")

        val payload = JSONObject()
            .put("model", model)
            .put("messages", serializeMessages(messages))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("$base/v1/chat/completions", apiKey, "POST")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            val out: OutputStream = conn.outputStream
            out.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return@withContext Result(false, error = "HTTP $code：${summarizeError(body)}")
            }
            val parsed = parseResponse(body)
            Result(true, parsed.content.ifBlank { body.take(200) })
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, error = "请求超时，请检查服务响应速度")
        } catch (e: java.net.ConnectException) {
            Result(false, error = "连接被拒绝，请检查地址与端口")
        } catch (e: Exception) {
            Result(false, error = e.message ?: "未知错误")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 带工具定义的对话（OpenAI function calling）。模型若返回 tool_calls，
     * 调用方需本地执行工具并把结果以 role=tool 回填后再次调用本方法。
     * 若服务明确不支持 tools（400/422 且错误信息含 tool/function），自动降级为普通对话，
     * 保证不支持工具调用的模型仍能正常聊天。
     */
    suspend fun chatWithTools(
        baseRaw: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: JSONArray,
        temperature: Double = 0.0,
        maxTokens: Int = 1024
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseRaw)
        if (base.isBlank()) return@withContext Result(false, error = "服务地址为空")
        if (model.isBlank()) return@withContext Result(false, error = "模型名为空")

        val payload = JSONObject()
            .put("model", model)
            .put("messages", serializeMessages(messages))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("$base/v1/chat/completions", apiKey, "POST")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                val msg = summarizeError(body)
                // v0.9.2：不再降级为纯文本聊天，避免模型「假装操作」却无法调用工具。
                if ((code == 400 || code == 422) &&
                    (msg.contains("tool", ignoreCase = true) || msg.contains("function", ignoreCase = true))
                ) {
                    return@withContext Result(false,
                        error = "模型不支持原生 Function Calling，请更换支持工具调用的模型：$msg")
                }
                return@withContext Result(false, error = "HTTP $code：$msg")
            }
            Result(true, parseResponse(body))
        } catch (e: java.net.SocketTimeoutException) {
            Result(false, error = "请求超时，请检查服务响应速度")
        } catch (e: java.net.ConnectException) {
            Result(false, error = "连接被拒绝，请检查地址与端口")
        } catch (e: Exception) {
            Result(false, error = e.message ?: "未知错误")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * SSE 流式对话（OpenAI stream=true）。onToken 逐块回调文本增量供 UI 实时渲染；
     * tool_calls 的 arguments 会跨多个 chunk 分片到达，按 index 累积后再交给调用方执行。
     * 服务端若在末帧返回 usage 则一并解析。失败时抛出由调用方转为普通错误。
     */
    suspend fun chatWithToolsStream(
        baseRaw: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: JSONArray,
        onToken: (String) -> Unit,
        temperature: Double = 0.0,
        maxTokens: Int = 1536,
        readTimeoutMs: Int = 60_000
    ): ChatResponse = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseRaw)
        if (base.isBlank()) throw IllegalStateException("服务地址为空")
        val payload = JSONObject()
            .put("model", model)
            .put("messages", serializeMessages(messages))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("tools", tools)
            .put("tool_choice", "auto")
            .toString()

        val contentSb = StringBuilder()
        // index -> (id, name, arguments)
        val calls = LinkedHashMap<Int, Triple<String, String, StringBuilder>>()
        var usage: TokenUsage? = null

        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("$base/v1/chat/completions", apiKey, "POST")
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = readBody(conn)
                throw IllegalStateException("HTTP $code：${summarizeError(body)}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            reader.use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    val s = line.trim()
                    if (!s.startsWith("data:")) continue
                    val data = s.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") {
                        if (data == "[DONE]") break else continue
                    }
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    // usage 可能在独立末帧（choices 为空）
                    chunk.optJSONObject("usage")?.let { u ->
                        usage = TokenUsage(
                            u.optInt("prompt_tokens", u.optInt("input_tokens", 0)),
                            u.optInt("completion_tokens", u.optInt("output_tokens", 0)),
                            u.optInt("total_tokens", 0)
                        )
                    }
                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val text = delta.optString("content", "")
                    if (text.isNotEmpty()) {
                        contentSb.append(text)
                        onToken(text)
                    }
                    val tcs = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until tcs.length()) {
                        val tc = tcs.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", 0)
                        val fn = tc.optJSONObject("function")
                        val id = tc.optString("id", "")
                        val name = fn?.optString("name", "").orEmpty()
                        val args = fn?.optString("arguments", "").orEmpty()
                        val prev = calls[idx] ?: Triple("", "", StringBuilder())
                        calls[idx] = Triple(
                            if (id.isNotBlank()) id else prev.first,
                            if (name.isNotBlank()) prev.second + name else prev.second,
                            prev.third.append(args)
                        )
                    }
                }
            }
            val toolCalls = calls.entries.sortedBy { it.key }.map { (_, triple) ->
                ToolCall(triple.first, triple.second, triple.third.toString().ifBlank { "{}" })
            }
            ChatResponse(contentSb.toString(), toolCalls, usage)
        } finally {
            conn?.disconnect()
        }
    }

    private fun summarizeError(body: String): String {
        if (body.isBlank()) return "无响应内容"
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            val msg = err?.optString("message", "") ?: obj.optString("message", "")
            (msg.ifBlank { body }).take(180)
        } catch (e: Exception) {
            body.take(180)
        }
    }
}
