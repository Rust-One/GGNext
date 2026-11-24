package com.littlecodesauce.ggnext.ai

import com.littlecodesauce.ggnext.core.Session
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 对话历史持久化层（v0.9.4）。
 *
 * v0.9.1 起唯一真相源由 [AiChatEngine] 以 Compose 快照列表持有；本对象负责序列化到
 * Session 的 DataStore 并在启动时读回。v0.9.4 支持多会话：一个 JSON 对象保存
 * 当前会话 id 与全部会话（消息 + 各自 usage）。同时兼容旧版单会话 key，首次升级自动迁移。
 */
object AiChatStore {
    private const val SESSIONS_KEY = "ai_chat_sessions"
    private const val LEGACY_KEY = "ai_chat_history"
    private const val MAX_MESSAGES = 200
    private const val MAX_SESSIONS = 12

    data class StoredSession(
        val id: String,
        val title: String,
        val messages: List<AiClient.ChatMessage>,
        val promptTokens: Long,
        val completionTokens: Long,
        val createdAt: Long
    )

    /** 读取全部会话与当前会话 id。兼容旧版单会话数据。 */
    fun loadSessions(): Pair<String, List<StoredSession>> {
        val raw = Session.ui.value.settings[SESSIONS_KEY].orEmpty()
        if (raw.isNotBlank()) {
            runCatching {
                val root = JSONObject(raw)
                val active = root.optString("active", "")
                val arr = root.optJSONArray("sessions") ?: return@runCatching
                val out = ArrayList<StoredSession>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        StoredSession(
                            id = o.optString("id", ""),
                            title = o.optString("title", ""),
                            messages = parseMessages(o.optJSONArray("messages")),
                            promptTokens = o.optLong("pt", 0),
                            completionTokens = o.optLong("ct", 0),
                            createdAt = o.optLong("at", 0)
                        )
                    )
                }
                return active to out
            }
        }
        // 迁移旧版：单个会话
        val legacy = Session.ui.value.settings[LEGACY_KEY].orEmpty()
        if (legacy.isNotBlank()) {
            val msgs = runCatching { parseMessages(JSONArray(legacy)) }.getOrDefault(emptyList())
            val id = "main"
            return id to listOf(StoredSession(id, "默认会话", msgs, 0, 0, System.currentTimeMillis()))
        }
        return "" to emptyList()
    }

    /** 保存全部会话（超额时保留最近 MAX_SESSIONS 个）。 */
    fun saveSessions(activeId: String, sessions: List<StoredSession>) {
        val root = JSONObject().put("active", activeId)
        val arr = JSONArray()
        val keep = sessions.takeLast(MAX_SESSIONS)
        for (s in keep) {
            val o = JSONObject()
                .put("id", s.id)
                .put("title", s.title)
                .put("pt", s.promptTokens)
                .put("ct", s.completionTokens)
                .put("at", s.createdAt)
                .put("messages", serializeMessages(s.messages))
            arr.put(o)
        }
        root.put("sessions", arr)
        Session.setSetting(SESSIONS_KEY, root.toString())
    }

    /** 读取持久化历史（供引擎初始化，兼容调用方）。 */
    fun load(): List<AiClient.ChatMessage> = loadSessions().second
        .firstOrNull { it.id == loadSessions().first }
        ?.messages
        ?: emptyList()

    /** 保存当前消息列表到主会话（兼容调用方）。 */
    fun save(all: List<AiClient.ChatMessage>) {
        val (active, sessions) = loadSessions()
        val id = active.ifBlank { "main" }
        val updated = sessions.toMutableList()
        val idx = updated.indexOfFirst { it.id == id }
        val s = StoredSession(
            id = id,
            title = if (idx >= 0) updated[idx].title else "默认会话",
            messages = all,
            promptTokens = if (idx >= 0) updated[idx].promptTokens else 0,
            completionTokens = if (idx >= 0) updated[idx].completionTokens else 0,
            createdAt = if (idx >= 0) updated[idx].createdAt else System.currentTimeMillis()
        )
        if (idx >= 0) updated[idx] = s else updated.add(s)
        saveSessions(id, updated)
    }

    fun clear() {
        Session.setSetting(SESSIONS_KEY, "")
        Session.setSetting(LEGACY_KEY, "")
    }

    private fun parseMessages(arr: JSONArray?): List<AiClient.ChatMessage> {
        if (arr == null) return emptyList()
        val out = ArrayList<AiClient.ChatMessage>()
        runCatching {
            val start = (arr.length() - MAX_MESSAGES).coerceAtLeast(0)
            for (i in start until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role", "")
                if (role.isBlank()) continue
                val calls = ArrayList<AiClient.ToolCall>()
                val ca = o.optJSONArray("tool_calls")
                if (ca != null) {
                    for (j in 0 until ca.length()) {
                        val c = ca.optJSONObject(j) ?: continue
                        calls.add(
                            AiClient.ToolCall(
                                id = c.optString("id", ""),
                                name = c.optString("name", ""),
                                arguments = c.optString("arguments", "{}")
                            )
                        )
                    }
                }
                out.add(
                    AiClient.ChatMessage(
                        role = role,
                        content = o.optString("content", ""),
                        toolCalls = calls.ifEmpty { null },
                        toolCallId = o.optString("tool_call_id", "").ifBlank { null }
                    )
                )
            }
        }
        return out
    }

    private fun serializeMessages(all: List<AiClient.ChatMessage>): JSONArray {
        val arr = JSONArray()
        val start = (all.size - MAX_MESSAGES).coerceAtLeast(0)
        for (i in start until all.size) {
            val m = all[i]
            val o = JSONObject().put("role", m.role).put("content", m.content)
            m.toolCallId?.let { o.put("tool_call_id", it) }
            val calls = m.toolCalls
            if (!calls.isNullOrEmpty()) {
                val ca = JSONArray()
                for (c in calls) {
                    ca.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("name", c.name)
                            .put("arguments", c.arguments)
                    )
                }
                o.put("tool_calls", ca)
            }
            arr.put(o)
        }
        return arr
    }
}
