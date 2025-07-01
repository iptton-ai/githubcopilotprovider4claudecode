package com.github.copilot.llmprovider.util

import com.github.copilot.llmprovider.model.*
import mu.KotlinLogging

/**
 * 请求响应日志格式化工具
 */
object RequestResponseLogger {
    private val logger = KotlinLogging.logger {}

    /**
     * 记录 OpenAI 聊天完成请求（简化版）
     */
    fun logOpenAIRequest(request: OpenAIChatCompletionRequest) {
        val formattedRequest = buildString {
            appendLine("🤖 Request: ${request.model}")
            appendLine("💬 Messages:")
            request.messages.forEachIndexed { index, message ->
                val content = message.content?.take(100) ?: ""
                val truncated = if ((message.content?.length ?: 0) > 100) "..." else ""
                appendLine("  ${index + 1}. ${formatRole(message.role)}: $content$truncated")
            }
            if (request.tools?.isNotEmpty() == true) {
                appendLine("🛠️  Tools: ${request.tools.size} available")
            }
        }

        println(formattedRequest)
    }

    /**
     * 记录 Claude 消息请求
     */
    fun logClaudeRequest(request: ClaudeMessageRequest) {
        val formattedRequest = buildString {
            appendLine("🧠 Request: ${request.model}")
            appendLine("💬 Messages:")
            request.messages.forEachIndexed { index, message ->
                val content = ClaudeContentHelper.extractText(message.content)
                val truncated = if (content.length > 100) content.take(100) + "..." else content
                appendLine("  ${index + 1}. ${formatRole(message.role)}: $truncated")
            }
            if (request.tools?.isNotEmpty() == true) {
                appendLine("🛠️  Tools: ${request.tools.size} available")
            }
        }

        println(formattedRequest)
    }

    /**
     * 记录 OpenAI 聊天完成响应
     */
    fun logOpenAIResponse(response: OpenAIChatCompletionResponse) {
        val formattedResponse = buildString {
            appendLine("✅ Response: ${response.model}")
            appendLine("📊 Tokens: ${response.usage?.promptTokens ?: 0} + ${response.usage?.completionTokens ?: 0} = ${response.usage?.totalTokens ?: 0}")

            if (response.choices.isNotEmpty()) {
                response.choices.forEachIndexed { index, choice ->
                    val content = choice.message?.content ?: choice.delta?.content ?: ""
                    if (content.isNotBlank()) {
                        val truncated = if (content.length > 200) content.take(200) + "..." else content
                        appendLine("🤖 ${formatRole("assistant")}: $truncated")
                    }
                    choice.finishReason?.let { reason ->
                        appendLine("🏁 Finish: $reason")
                    }
                }
            }
        }

        println(formattedResponse)
    }

    /**
     * 记录 Claude 消息响应
     */
    fun logClaudeResponse(response: ClaudeMessageResponse) {
        val formattedResponse = buildString {
            appendLine("✅ Response: ${response.model}")
            appendLine("📊 Tokens: ${response.usage.inputTokens} + ${response.usage.outputTokens} = ${response.usage.inputTokens + response.usage.outputTokens}")

            if (response.content.isNotEmpty()) {
                response.content.forEachIndexed { index, block ->
                    when (block.type) {
                        "text" -> {
                            val text = block.text ?: ""
                            val truncated = if (text.length > 200) text.take(200) + "..." else text
                            appendLine("🧠 ${formatRole("assistant")}: $truncated")
                        }
                        "tool_use" -> {
                            appendLine("🔧 Tool: ${block.name ?: "unknown"}")
                        }
                        "tool_result" -> {
                            val content = block.content ?: ""
                            val truncated = if (content.length > 100) content.take(100) + "..." else content
                            appendLine("📋 Tool Result: $truncated")
                        }
                    }
                }
            }
            appendLine("🏁 Stop: ${response.stopReason}")
        }

        println(formattedResponse)
    }

    /**
     * 记录流式响应块（简化版）
     */
    fun logStreamChunk(chunk: String, isFirst: Boolean = false, isLast: Boolean = false) {
        if (isFirst) {
            println("🌊 Streaming...")
        }

        if (chunk.isNotBlank() && chunk != "[DONE]") {
            print(chunk) // 实时显示内容
        }

        if (isLast) {
            println("\n🏁 Stream completed")
        }
    }

    /**
     * 记录错误
     */
    fun logError(error: String, details: String? = null) {
        val formattedError = buildString {
            appendLine("❌ Error Occurred")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("🚨 Error: $error")
            details?.let {
                appendLine("📋 Details: $it")
            }
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        
        logger.error { formattedError }
        println(formattedError)
    }

    /**
     * 格式化角色显示
     */
    private fun formatRole(role: String): String {
        return when (role.lowercase()) {
            "user" -> "👤 User"
            "assistant" -> "🤖 Assistant"
            "system" -> "🔧 System"
            "tool" -> "🛠️  Tool"
            else -> "❓ $role"
        }
    }
}
