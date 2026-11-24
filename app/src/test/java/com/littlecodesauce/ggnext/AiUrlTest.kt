package com.littlecodesauce.ggnext

import com.littlecodesauce.ggnext.ai.AiUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.0: AI 服务地址解析的纯 JVM 单元测试（新增功能，无需 Android 运行时）。
 */
class AiUrlTest {

    @Test
    fun normalizeAddsHttpSchemeByDefault() {
        assertEquals("http://api.openai.com", AiUrl.normalizeBaseUrl("api.openai.com"))
        assertEquals("http://192.168.1.10:8080", AiUrl.normalizeBaseUrl("192.168.1.10:8080"))
    }

    @Test
    fun normalizePreservesHttps() {
        assertEquals("https://api.openai.com", AiUrl.normalizeBaseUrl("https://api.openai.com"))
        assertEquals("HTTPS://example.com", AiUrl.normalizeBaseUrl("HTTPS://example.com"))
    }

    @Test
    fun normalizeTrimsTrailingSlashes() {
        assertEquals("http://localhost:11434", AiUrl.normalizeBaseUrl("localhost:11434///"))
        assertEquals("https://example.com", AiUrl.normalizeBaseUrl("https://example.com/"))
    }

    @Test
    fun normalizeTrimsWhitespace() {
        assertEquals("https://example.com", AiUrl.normalizeBaseUrl("  https://example.com  "))
        assertEquals("", AiUrl.normalizeBaseUrl("   "))
    }

    @Test
    fun cleartextDetection() {
        assertTrue(AiUrl.isCleartext("192.168.1.10:8080"))
        assertTrue(AiUrl.isCleartext("http://example.com"))
        assertFalse(AiUrl.isCleartext("https://example.com"))
    }

    @Test
    fun endpointJoining() {
        assertEquals(
            "https://api.openai.com/v1/models",
            AiUrl.endpoint("https://api.openai.com", "/v1/models")
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            AiUrl.endpoint("localhost:11434/", "v1/chat/completions")
        )
    }
}
