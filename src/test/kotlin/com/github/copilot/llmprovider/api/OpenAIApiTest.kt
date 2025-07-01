package com.github.copilot.llmprovider.api

import io.mockk.mockk
import com.github.copilot.llmprovider.auth.AuthManager
import com.github.copilot.llmprovider.model.OpenAIChatCompletionRequest
import com.github.copilot.llmprovider.model.OpenAIMessage
import com.github.copilot.llmprovider.server.configureServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 测试 OpenAI API 兼容接口
 * 
 * 测试理由：
 * - 验证 /v1/chat/completions 端点能正确处理请求
 * - 确保支持流式和非流式响应
 * - 验证请求验证和错误处理
 * - 测试 tool calls 功能
 */
class OpenAIApiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val mockAuthManager = mockk<AuthManager>(relaxed = true)

    @Test
    fun `should handle chat completions request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = OpenAIChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(
                OpenAIMessage(
                    role = "user",
                    content = "Hello, world!"
                )
            ),
            stream = false
        )

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAIChatCompletionRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.contains("choices"))
    }

    @Test
    fun `should handle streaming chat completions request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = OpenAIChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(
                OpenAIMessage(
                    role = "user",
                    content = "Hello, world!"
                )
            ),
            stream = true
        )

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAIChatCompletionRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `should validate required fields in request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"messages": []}""") // 缺少 model 字段
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.contains("error"))
    }

    @Test
    fun `should handle tool calls in request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = OpenAIChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(
                OpenAIMessage(
                    role = "user",
                    content = "What's the weather like in Beijing?"
                )
            ),
            tools = listOf(
                com.github.copilot.llmprovider.model.OpenAITool(
                    type = "function",
                    function = com.github.copilot.llmprovider.model.OpenAIFunctionDefinition(
                        name = "get_weather",
                        description = "Get weather information for a location"
                    )
                )
            )
        )

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAIChatCompletionRequest.serializer(), request))
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `should handle invalid JSON in request`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("invalid json")
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `should handle unsupported model`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Arrange
        val request = OpenAIChatCompletionRequest(
            model = "unsupported-model",
            messages = listOf(
                OpenAIMessage(
                    role = "user",
                    content = "Hello"
                )
            )
        )

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OpenAIChatCompletionRequest.serializer(), request))
        }

        // Assert
        // 应该返回错误或者转换为支持的模型
        assertTrue(response.status.value in 200..499)
    }
}
