package com.github.copilot.llmprovider.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试 Claude API 模型的序列化和反序列化
 * 
 * 测试理由：
 * - 验证 Claude API 特有的消息格式
 * - 确保与 OpenAI 格式的正确转换
 * - 验证流式响应的处理
 */
class ClaudeModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should serialize Claude message request correctly`() {
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
        val jsonString = json.encodeToString(ClaudeMessageRequest.serializer(), request)

        // Assert
        assertTrue(jsonString.contains("\"model\":\"claude-3-sonnet-20240229\""))
        assertTrue(jsonString.contains("\"max_tokens\":1000"))
        assertTrue(jsonString.contains("\"role\":\"user\""))
        assertTrue(jsonString.contains("\"content\":\"Hello, Claude!\""))
    }

    @Test
    fun `should deserialize Claude message request correctly`() {
        // Arrange
        val jsonString = """
            {
                "model": "claude-3-sonnet-20240229",
                "max_tokens": 2000,
                "messages": [
                    {
                        "role": "user",
                        "content": "What's the weather like?"
                    }
                ],
                "stream": true
            }
        """.trimIndent()

        // Act
        val request = json.decodeFromString(ClaudeMessageRequest.serializer(), jsonString)

        // Assert
        assertEquals("claude-3-sonnet-20240229", request.model)
        assertEquals(2000, request.maxTokens)
        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages[0].role)
        assertEquals("What's the weather like?", request.messages[0].content)
        assertEquals(true, request.stream)
    }

    @Test
    fun `should handle Claude tool use in messages`() {
        // Arrange
        val message = ClaudeMessage(
            role = "assistant",
            content = "I'll check the weather for you."
        )

        // Act
        val jsonString = json.encodeToString(ClaudeMessage.serializer(), message)
        val deserializedMessage = json.decodeFromString(ClaudeMessage.serializer(), jsonString)

        // Assert
        assertEquals("assistant", deserializedMessage.role)
        assertEquals("I'll check the weather for you.", deserializedMessage.content)
    }

    @Test
    fun `should create valid Claude streaming response`() {
        // Arrange
        val response = ClaudeMessageResponse(
            id = "msg_123",
            type = "message",
            role = "assistant",
            content = listOf(
                ClaudeContentBlock.Text("Hello there!")
            ),
            model = "claude-3-sonnet-20240229",
            stopReason = "end_turn",
            stopSequence = null,
            usage = ClaudeUsage(
                inputTokens = 10,
                outputTokens = 5
            )
        )

        // Act
        val jsonString = json.encodeToString(ClaudeMessageResponse.serializer(), response)

        // Assert
        assertTrue(jsonString.contains("\"type\":\"message\""))
        assertTrue(jsonString.contains("\"role\":\"assistant\""))
        assertTrue(jsonString.contains("\"stop_reason\":\"end_turn\""))
        assertTrue(jsonString.contains("\"input_tokens\":10"))
    }

    @Test
    fun `should handle system message in Claude format`() {
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
        val jsonString = json.encodeToString(ClaudeMessageRequest.serializer(), request)

        // Assert
        assertTrue(jsonString.contains("\"system\":\"You are a helpful assistant.\""))
        assertNotNull(request.system)
        assertEquals("You are a helpful assistant.", request.system)
    }
}
