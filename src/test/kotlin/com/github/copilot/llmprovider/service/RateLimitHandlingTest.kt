package com.github.copilot.llmprovider.service

import com.github.copilot.llmprovider.auth.AuthManager
import com.github.copilot.llmprovider.exception.RateLimitException
import com.github.copilot.llmprovider.model.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 测试 429 错误处理和模型降级功能
 */
class RateLimitHandlingTest {

    @Test
    fun `should throw RateLimitException on 429 response`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回 429 错误
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("""{"type":"error","error":{"type":"rate_limit_error","message":"Too Many Requests"}}"""),
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试应该抛出 RateLimitException
        val exception = assertThrows<RateLimitException> {
            copilotService.sendChatCompletion(
                apiToken = "test-token",
                model = "claude-sonnet-4",
                messages = listOf(mapOf("role" to "user", "content" to "Hello"))
            )
        }

        assertEquals(429, exception.statusCode)
        assertTrue(exception.message!!.contains("Rate limit exceeded"))
    }

    @Test
    fun `should throw RateLimitException on 500 response with 429 content`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回包含 429 信息的 500 错误
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("""{"type":"error","error":{"type":"internal_error","message":"Failed to process request: Chat completion failed: 429 Too Many Requests"}}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试应该抛出 RateLimitException
        val exception = assertThrows<RateLimitException> {
            copilotService.sendChatCompletion(
                apiToken = "test-token",
                model = "claude-sonnet-4",
                messages = listOf(mapOf("role" to "user", "content" to "Hello"))
            )
        }

        assertEquals(500, exception.statusCode)
        assertTrue(exception.message!!.contains("Rate limit exceeded"))
    }

    @Test
    fun `should throw RateLimitException on 500 response with quota exceeded content`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回包含 "quota exceeded" 信息的 500 错误
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("""{"type":"error","error":{"type":"internal_error","message":"Failed to process request: Rate limit exceeded: quota exceeded"}}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试应该抛出 RateLimitException
        val exception = assertThrows<RateLimitException> {
            copilotService.sendChatCompletion(
                apiToken = "test-token",
                model = "claude-sonnet-4",
                messages = listOf(mapOf("role" to "user", "content" to "Hello"))
            )
        }

        assertEquals(500, exception.statusCode)
        assertTrue(exception.message!!.contains("Rate limit exceeded"))
        assertTrue(exception.message!!.contains("quota exceeded"))
    }

    @Test
    fun `should get correct fallback model for claude-sonnet-4`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回模型列表
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/models" -> respond(
                    content = ByteReadChannel("""
                        {
                            "data": [
                                {"id": "claude-sonnet-4", "object": "model"},
                                {"id": "claude-3.7-sonnet", "object": "model"},
                                {"id": "claude-3.5-sonnet", "object": "model"},
                                {"id": "gpt-4o", "object": "model"}
                            ]
                        }
                    """.trimIndent()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.NotFound
                )
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试从 claude-sonnet-4 降级
        val fallbackModel = copilotService.getFallbackModelForRateLimit("test-token", "claude-sonnet-4")
        assertEquals("claude-3.7-sonnet", fallbackModel)
    }

    @Test
    fun `should get correct fallback model for claude-3_7-sonnet`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回模型列表
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/models" -> respond(
                    content = ByteReadChannel("""
                        {
                            "data": [
                                {"id": "claude-3.7-sonnet", "object": "model"},
                                {"id": "claude-3.5-sonnet", "object": "model"},
                                {"id": "claude-3-sonnet-20240229", "object": "model"},
                                {"id": "gpt-4o", "object": "model"}
                            ]
                        }
                    """.trimIndent()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.NotFound
                )
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试从 claude-3.7-sonnet 降级
        val fallbackModel = copilotService.getFallbackModelForRateLimit("test-token", "claude-3.7-sonnet")
        assertEquals("claude-3.5-sonnet", fallbackModel)
    }

    @Test
    fun `should fallback to GPT when no Claude models available`() = runBlocking {
        // 创建模拟的 HTTP 引擎，返回只有 GPT 模型的列表
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/models" -> respond(
                    content = ByteReadChannel("""
                        {
                            "data": [
                                {"id": "gpt-4o", "object": "model"},
                                {"id": "gpt-3.5-turbo", "object": "model"}
                            ]
                        }
                    """.trimIndent()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.NotFound
                )
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // 测试当没有 Claude 模型时降级到 GPT
        val fallbackModel = copilotService.getFallbackModelForRateLimit("test-token", "claude-sonnet-4")
        assertEquals("gpt-4o", fallbackModel)
    }
}
