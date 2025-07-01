package com.github.copilot.llmprovider.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI Chat Completion Request
 */
@Serializable
data class OpenAIChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    val tools: List<OpenAITool>? = null,
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val stop: JsonElement? = null
)

/**
 * OpenAI Message
 */
@Serializable
data class OpenAIMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
)

/**
 * OpenAI Tool Call
 */
@Serializable
data class OpenAIToolCall(
    val id: String,
    val type: String,
    val function: OpenAIFunction
)

/**
 * OpenAI Function
 */
@Serializable
data class OpenAIFunction(
    val name: String,
    val arguments: String
)

/**
 * OpenAI Tool
 */
@Serializable
data class OpenAITool(
    val type: String,
    val function: OpenAIFunctionDefinition
)

/**
 * OpenAI Function Definition
 */
@Serializable
data class OpenAIFunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null
)

/**
 * OpenAI Chat Completion Response
 */
@Serializable
data class OpenAIChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val objectType: String = "chat.completion",  // 设为可选，默认值
    val created: Long = 0,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null
)

/**
 * OpenAI Choice
 */
@Serializable
data class OpenAIChoice(
    val index: Int = 0,  // 设为可选，默认值为 0
    val message: OpenAIMessage? = null,
    val delta: OpenAIDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/**
 * OpenAI Delta (for streaming)
 */
@Serializable
data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null
)

/**
 * OpenAI Usage
 */
@Serializable
data class OpenAIUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
    @SerialName("prompt_tokens_details")
    val promptTokensDetails: JsonElement? = null  // GitHub Copilot 特有字段
)

/**
 * OpenAI Error Response
 */
@Serializable
data class OpenAIErrorResponse(
    val error: OpenAIError
)

/**
 * OpenAI Error
 */
@Serializable
data class OpenAIError(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null
)
