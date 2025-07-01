package com.github.copilot.llmprovider.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试 OpenAI API 模型的序列化和反序列化
 * 
 * 测试理由：
 * - 验证 JSON 序列化/反序列化的正确性
 * - 确保必填字段验证
 * - 验证 tool calls 的正确处理
 * - 测试流式响应格式
 */
class OpenAIModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `should serialize OpenAI chat completion request correctly`() {
        // Arrange
        val request = OpenAIChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(
                OpenAIMessage(
                    role = "user",
                    content = "Hello, world!"
                )
            ),
            stream = false,
            temperature = 0.7,
            maxTokens = 1000
        )

        // Act
        val jsonString = json.encodeToString(OpenAIChatCompletionRequest.serializer(), request)

        // Assert
        assertTrue(jsonString.contains("\"model\":\"gpt-4\""))
        assertTrue(jsonString.contains("\"role\":\"user\""))
        assertTrue(jsonString.contains("\"content\":\"Hello, world!\""))
        assertTrue(jsonString.contains("\"stream\":false"))
    }

    @Test
    fun `should deserialize OpenAI chat completion request correctly`() {
        // Arrange
        val jsonString = """
            {
                "model": "gpt-4",
                "messages": [
                    {
                        "role": "user",
                        "content": "Hello, world!"
                    }
                ],
                "stream": true,
                "temperature": 0.8
            }
        """.trimIndent()

        // Act
        val request = json.decodeFromString(OpenAIChatCompletionRequest.serializer(), jsonString)

        // Assert
        assertEquals("gpt-4", request.model)
        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages[0].role)
        assertEquals("Hello, world!", request.messages[0].content)
        assertEquals(true, request.stream)
        assertEquals(0.8, request.temperature)
    }

    @Test
    fun `should handle tool calls in messages`() {
        // Arrange
        val message = OpenAIMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                OpenAIToolCall(
                    id = "call_123",
                    type = "function",
                    function = OpenAIFunction(
                        name = "get_weather",
                        arguments = """{"location": "Beijing"}"""
                    )
                )
            )
        )

        // Act
        val jsonString = json.encodeToString(OpenAIMessage.serializer(), message)
        val deserializedMessage = json.decodeFromString(OpenAIMessage.serializer(), jsonString)

        // Assert
        assertEquals("assistant", deserializedMessage.role)
        assertEquals(null, deserializedMessage.content)
        assertNotNull(deserializedMessage.toolCalls)
        assertEquals(1, deserializedMessage.toolCalls!!.size)
        assertEquals("call_123", deserializedMessage.toolCalls!![0].id)
        assertEquals("get_weather", deserializedMessage.toolCalls!![0].function.name)
    }

    @Test
    fun `should create valid streaming response`() {
        // Arrange
        val choice = OpenAIChoice(
            index = 0,
            delta = OpenAIDelta(
                role = "assistant",
                content = "Hello"
            ),
            finishReason = null
        )
        
        val response = OpenAIChatCompletionResponse(
            id = "chatcmpl-123",
            objectType = "chat.completion.chunk",
            created = 1234567890,
            model = "gpt-4",
            choices = listOf(choice)
        )

        // Act
        val jsonString = json.encodeToString(OpenAIChatCompletionResponse.serializer(), response)

        // Assert
        assertTrue(jsonString.contains("\"object\":\"chat.completion.chunk\""))
        assertTrue(jsonString.contains("\"delta\""))
        assertTrue(jsonString.contains("\"content\":\"Hello\""))
    }

    @Test
    fun `should validate required fields in request`() {
        // Arrange
        val invalidJson = """
            {
                "messages": [
                    {
                        "role": "user",
                        "content": "Hello"
                    }
                ]
            }
        """.trimIndent()

        // Act & Assert
        assertThrows<Exception> {
            json.decodeFromString(OpenAIChatCompletionRequest.serializer(), invalidJson)
        }
    }
}
