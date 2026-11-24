package com.littlecodesauce.ggnext.ai

/**
 * 纯 JVM 的 AI 地址工具（无 Android / org.json / 协程依赖），便于本地单元测试。
 */
object AiUrl {

    /** 规范化用户输入的 base URL：补全协议、去除末尾斜杠。支持 http/https、域名或 ip:端口。 */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        while (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /** 是否为明文 http。 */
    fun isCleartext(raw: String): Boolean =
        normalizeBaseUrl(raw).startsWith("http://", ignoreCase = true)

    /** 拼接 OpenAI 兼容接口路径。 */
    fun endpoint(raw: String, path: String): String {
        val base = normalizeBaseUrl(raw)
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }
}
