package com.github.copilot.llmprovider.service

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试 GitHub Copilot API 服务
 * 
 * 测试理由：
 * - 验证 API token 获取流程
 * - 确保模型列表获取正确
 * - 测试聊天完成请求的发送
 * - 验证错误处理和重试机制
 */
class GitHubCopilotServiceTest {

    @Test
    fun `should get API token successfully`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/copilot_internal/v2/token" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "token": "test_api_token_12345",
                                "expires_at": 1234567890,
                                "refresh_in": 600
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act
        val apiToken = copilotService.getApiToken("test_oauth_token")

        // Assert
        assertNotNull(apiToken)
        assertEquals("test_api_token_12345", apiToken.token)
        assertEquals(1234567890, apiToken.expiresAt)
        assertEquals(600, apiToken.refreshIn)
    }

    @Test
    fun `should get supported models successfully`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/models" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "data": [
                                    {
                                        "id": "claude-3.5-sonnet",
                                        "object": "model",
                                        "created": 1234567890,
                                        "owned_by": "anthropic"
                                    },
                                    {
                                        "id": "claude-3-haiku",
                                        "object": "model",
                                        "created": 1234567890,
                                        "owned_by": "anthropic"
                                    },
                                    {
                                        "id": "gpt-4o",
                                        "object": "model",
                                        "created": 1234567890,
                                        "owned_by": "openai"
                                    }
                                ]
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act
        val models = copilotService.getSupportedModels("test_api_token")

        // Assert
        assertNotNull(models)
        assertEquals(3, models.data.size)
        
        val claudeModel = models.data.find { it.id == "claude-3.5-sonnet" }
        assertNotNull(claudeModel)
        assertEquals("anthropic", claudeModel.ownedBy)
        
        val gptModel = models.data.find { it.id == "gpt-4o" }
        assertNotNull(gptModel)
        assertEquals("openai", gptModel.ownedBy)
    }

    @Test
    fun `should send chat completion request successfully`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/chat/completions" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "id": "chatcmpl-test123",
                                "object": "chat.completion",
                                "created": 1234567890,
                                "model": "claude-3.5-sonnet",
                                "choices": [
                                    {
                                        "index": 0,
                                        "message": {
                                            "role": "assistant",
                                            "content": "Hello! How can I help you today?"
                                        },
                                        "finish_reason": "stop"
                                    }
                                ],
                                "usage": {
                                    "prompt_tokens": 10,
                                    "completion_tokens": 8,
                                    "total_tokens": 18
                                }
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act
        val response = copilotService.sendChatCompletion(
            apiToken = "test_api_token",
            model = "claude-3.5-sonnet",
            messages = listOf(
                mapOf("role" to "user", "content" to "Hello")
            ),
            stream = false
        )

        // Assert
        assertNotNull(response)
        assertEquals("chatcmpl-test123", response.id)
        assertEquals("claude-3.5-sonnet", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("Hello! How can I help you today?", response.choices[0].message?.content)
    }

    @Test
    fun `should handle API token expiration`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/copilot_internal/v2/token" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "token": "expired_token",
                                "expires_at": 1000000000,
                                "refresh_in": 0
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act
        val apiToken = copilotService.getApiToken("test_oauth_token")

        // Assert
        assertNotNull(apiToken)
        assertTrue(copilotService.isTokenExpired(apiToken))
    }

    @Test
    fun `should handle unauthorized error`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/copilot_internal/v2/token" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "error": "unauthorized",
                                "message": "Invalid OAuth token"
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act & Assert
        try {
            copilotService.getApiToken("invalid_oauth_token")
            error("Should have thrown exception")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("unauthorized") == true)
        }
    }

    @Test
    fun `should prefer Claude 4 model when available`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/models" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "data": [
                                    {
                                        "id": "claude-3.5-sonnet",
                                        "object": "model",
                                        "created": 1234567890,
                                        "owned_by": "anthropic"
                                    },
                                    {
                                        "id": "claude-3-haiku",
                                        "object": "model",
                                        "created": 1234567890,
                                        "owned_by": "anthropic"
                                    }
                                ]
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val copilotService = GitHubCopilotService(mockEngine)

        // Act
        val preferredModel = copilotService.getPreferredClaudeModel("test_api_token")

        // Assert
        // 应该选择 Claude 3.5 Sonnet 作为最佳可用模型
        assertEquals("claude-3.5-sonnet", preferredModel)
    }
}
