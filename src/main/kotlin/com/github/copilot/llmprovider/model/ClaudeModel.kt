package com.github.copilot.llmprovider.model

import kotlinx.serialization.*
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

/**
 * Claude Message Request
 */
@Serializable
data class ClaudeMessageRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val messages: List<ClaudeMessage>,
    val system: JsonElement? = null,  // 可以是字符串或数组
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("top_k")
    val topK: Int? = null,
    val tools: List<JsonElement>? = null,  // 工具定义可能很复杂
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    @SerialName("stop_sequences")
    val stopSequences: List<String>? = null,
    @SerialName("cache_control")
    val cacheControl: JsonElement? = null,
    val metadata: JsonElement? = null
)

/**
 * Claude Message
 */
@Serializable
data class ClaudeMessage(
    val role: String,
    val content: JsonElement,  // 可以是字符串或数组
    @SerialName("cache_control")
    val cacheControl: JsonElement? = null
)

/**
 * Claude Content 辅助函数
 */
object ClaudeContentHelper {
    /**
     * 将 JsonElement 转换为文本字符串
     */
    fun extractText(content: JsonElement): String {
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> {
                content.jsonArray.joinToString("\n") { element ->
                    if (element is JsonObject) {
                        val type = element.jsonObject["type"]?.jsonPrimitive?.content
                        when (type) {
                            "text" -> element.jsonObject["text"]?.jsonPrimitive?.content ?: ""
                            "tool_use" -> {
                                val name = element.jsonObject["name"]?.jsonPrimitive?.content ?: "unknown"
                                val input = element.jsonObject["input"]?.toString() ?: "{}"
                                // 避免模型幻觉，使用自然语言描述
                                "I used the $name tool with parameters: $input"
                            }
                            "tool_result" -> {
                                val content = element.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                                if (content.isNotBlank()) {
                                    "The tool execution returned: $content"
                                } else {
                                    "The tool execution completed."
                                }
                            }
                            else -> "[${type ?: "unknown"}]"
                        }
                    } else {
                        element.toString()
                    }
                }
            }
            is JsonObject -> {
                val type = content.jsonObject["type"]?.jsonPrimitive?.content
                when (type) {
                    "text" -> content.jsonObject["text"]?.jsonPrimitive?.content ?: ""
                    else -> content.toString()
                }
            }
            else -> content.toString()
        }
    }
}

/**
 * 简单的内容块表示 - 用于响应
 */
@Serializable
data class ClaudeContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    @SerialName("tool_use_id")
    val toolUseId: String? = null,
    val content: String? = null,
    @SerialName("is_error")
    val isError: Boolean? = null,
    @SerialName("cache_control")
    val cacheControl: JsonElement? = null
) {
    companion object {
        fun text(content: String) = ClaudeContentBlock(
            type = "text",
            text = content
        )

        fun toolUse(id: String, name: String, input: JsonElement) = ClaudeContentBlock(
            type = "tool_use",
            id = id,
            name = name,
            input = input
        )

        fun toolResult(toolUseId: String, content: String, isError: Boolean = false) = ClaudeContentBlock(
            type = "tool_result",
            toolUseId = toolUseId,
            content = content,
            isError = isError
        )
    }
}

/**
 * Claude Tool
 */
@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonElement
)

/**
 * Claude Message Response
 */
@Serializable
data class ClaudeMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ClaudeContentBlock>,
    val model: String,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    @SerialName("stop_sequence")
    val stopSequence: String? = null,
    val usage: ClaudeUsage
)

/**
 * Claude Usage
 */
@Serializable
data class ClaudeUsage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)

/**
 * Claude Error Response
 */
@Serializable
data class ClaudeErrorResponse(
    val type: String,
    val error: ClaudeError
)

/**
 * Claude Error
 */
@Serializable
data class ClaudeError(
    val type: String,
    val message: String
)


