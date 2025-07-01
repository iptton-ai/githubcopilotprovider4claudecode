package com.github.copilot.llmprovider.api

import com.github.copilot.llmprovider.auth.AuthManager
import com.github.copilot.llmprovider.model.ClaudeMessage
import com.github.copilot.llmprovider.model.ClaudeMessageRequest
import com.github.copilot.llmprovider.server.configureServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.mockk.mockk

/**
 * 测试 Claude API 兼容接口
 * 
 * 测试理由：
 * - 验证 /v1/messages 端点能正确处理请求
 * - 确保支持流式和非流式响应
 * - 验证请求验证和错误处理
 * - 测试 Claude 特有的消息格式
 */
class ClaudeApiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val mockAuthManager = mockk<AuthManager>(relaxed = true)

    @Test
    fun `should handle Claude messages request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = ClaudeMessageRequest(
            model = "claude-3-sonnet-20240229",
            maxTokens = 1000,
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = "Hello, Claude!"
                )
            ),
            stream = false
        )

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClaudeMessageRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.contains("content"))
    }

    @Test
    fun `should handle streaming Claude messages request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = ClaudeMessageRequest(
            model = "claude-3-sonnet-20240229",
            maxTokens = 1000,
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = "Hello, Claude!"
                )
            ),
            stream = true
        )

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClaudeMessageRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `should validate required fields in Claude request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages": []}""") // 缺少 model 和 max_tokens 字段
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.contains("error"))
    }

    @Test
    fun `should handle system message in Claude request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = ClaudeMessageRequest(
            model = "claude-3-sonnet-20240229",
            maxTokens = 1000,
            system = "You are a helpful assistant.",
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = "Hello!"
                )
            )
        )

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClaudeMessageRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `should handle invalid JSON in Claude request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody("invalid json")
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `should handle unsupported Claude model`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = ClaudeMessageRequest(
            model = "unsupported-claude-model",
            maxTokens = 1000,
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = "Hello"
                )
            )
        )

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClaudeMessageRequest.serializer(), request))
        }

        // Assert
        // 应该返回错误或者转换为支持的模型
        assertTrue(response.status.value in 200..499)
    }

    @Test
    fun `should handle max_tokens validation`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = ClaudeMessageRequest(
            model = "claude-3-sonnet-20240229",
            maxTokens = 0, // 无效的 max_tokens
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = "Hello"
                )
            )
        )

        // Act
        val response = client.post("/v1/messages") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ClaudeMessageRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
