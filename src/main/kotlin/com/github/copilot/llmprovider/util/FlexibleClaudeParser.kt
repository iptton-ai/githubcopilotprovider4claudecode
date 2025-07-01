package com.github.copilot.llmprovider.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mu.KotlinLogging

/**
 * 灵活的 Claude 请求解析器
 * 使用运行时类型检测 + Try-Catch 来处理动态字段
 */
object FlexibleClaudeParser {
    private val logger = KotlinLogging.logger {}
    
    /**
     * 灵活的 Claude 请求 - 使用 JsonElement 处理动态字段
     */
    @Serializable
    data class FlexibleClaudeRequest(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens")
        val maxTokens: Int,
        val messages: List<FlexibleMessage>,
        val system: String? = null,  // 解析后的系统消息文本
        val stream: Boolean = false,
        val temperature: Double? = null,
        @kotlinx.serialization.SerialName("top_p")
        val topP: Double? = null,
        @kotlinx.serialization.SerialName("top_k")
        val topK: Int? = null,
        val tools: List<JsonElement>? = null,
        @kotlinx.serialization.SerialName("tool_choice")
        val toolChoice: JsonElement? = null,
        @kotlinx.serialization.SerialName("stop_sequences")
        val stopSequences: List<String>? = null,
        @kotlinx.serialization.SerialName("cache_control")
        val cacheControl: JsonElement? = null,
        val metadata: JsonElement? = null
    )
    
    /**
     * 灵活的消息 - content 已解析为文本
     */
    @Serializable
    data class FlexibleMessage(
        val role: String,
        val content: String,  // 解析后的内容文本（用于显示）
        val originalContent: String? = null,  // 原始 JSON 内容（用于结构化处理）
        @kotlinx.serialization.SerialName("cache_control")
        val cacheControl: JsonElement? = null
    )
    
    /**
     * 解析 Claude 请求
     */
    fun parseClaudeRequest(jsonString: String): FlexibleClaudeRequest {
        try {
            // 首先解析为 JsonObject 来检查字段类型
            val jsonObject = Json.parseToJsonElement(jsonString).jsonObject
            
            // 解析基本字段
            val model = jsonObject["model"]?.jsonPrimitive?.content 
                ?: throw IllegalArgumentException("Missing required field: model")
            val maxTokens = jsonObject["max_tokens"]?.jsonPrimitive?.int 
                ?: throw IllegalArgumentException("Missing required field: max_tokens")
            
            // 解析 system 字段（字符串或数组）
            val systemText = parseSystemField(jsonObject["system"])
            
            // 解析 messages 字段
            val messagesArray = jsonObject["messages"]?.jsonArray 
                ?: throw IllegalArgumentException("Missing required field: messages")
            val messages = parseMessages(messagesArray)
            
            // 解析其他字段
            return FlexibleClaudeRequest(
                model = model,
                maxTokens = maxTokens,
                messages = messages,
                system = systemText,
                stream = jsonObject["stream"]?.jsonPrimitive?.boolean ?: false,
                temperature = jsonObject["temperature"]?.jsonPrimitive?.double,
                topP = jsonObject["top_p"]?.jsonPrimitive?.double,
                topK = jsonObject["top_k"]?.jsonPrimitive?.int,
                tools = jsonObject["tools"]?.jsonArray?.toList(),
                toolChoice = jsonObject["tool_choice"],
                stopSequences = jsonObject["stop_sequences"]?.jsonArray?.map { 
                    it.jsonPrimitive.content 
                },
                cacheControl = jsonObject["cache_control"],
                metadata = jsonObject["metadata"]
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse Claude request" }
            throw IllegalArgumentException("Invalid Claude request: ${e.message}", e)
        }
    }
    
    /**
     * 解析 system 字段 - 尝试字符串，失败则尝试数组
     */
    private fun parseSystemField(systemElement: JsonElement?): String? {
        if (systemElement == null) {
            logger.debug { "System field is null" }
            return null
        }

        return try {
            // 尝试作为字符串解析
            if (systemElement is JsonPrimitive) {
                val systemText = systemElement.content
                logger.info { "Parsed system as string: ${systemText.length} characters" }
                systemText
            } else {
                // 尝试作为数组解析
                logger.info { "Parsing system as array with ${systemElement.jsonArray.size} blocks" }
                val parsedText = parseContentArray(systemElement.jsonArray)
                logger.info { "Parsed system array to text: ${parsedText.length} characters" }
                logger.debug { "System array content preview: ${parsedText.take(200)}..." }
                parsedText
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse system field, using toString: $systemElement" }
            systemElement.toString()
        }
    }
    
    /**
     * 解析 messages 数组
     */
    private fun parseMessages(messagesArray: JsonArray): List<FlexibleMessage> {
        return messagesArray.map { messageElement ->
            val messageObject = messageElement.jsonObject
            
            val role = messageObject["role"]?.jsonPrimitive?.content 
                ?: throw IllegalArgumentException("Missing message role")
            
            val contentElement = messageObject["content"]
                ?: throw IllegalArgumentException("Missing message content")

            val contentText = parseContentField(contentElement)

            // 保存原始内容（如果是数组格式）
            val originalContent = if (contentElement is JsonArray) {
                contentElement.toString()
            } else null

            FlexibleMessage(
                role = role,
                content = contentText,
                originalContent = originalContent,
                cacheControl = messageObject["cache_control"]
            )
        }
    }
    
    /**
     * 解析 content 字段 - 尝试字符串，失败则尝试数组
     */
    private fun parseContentField(contentElement: JsonElement): String {
        return try {
            // 尝试作为字符串解析
            if (contentElement is JsonPrimitive) {
                contentElement.content
            } else {
                // 尝试作为数组解析
                parseContentArray(contentElement.jsonArray)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse content field, using toString: $contentElement" }
            contentElement.toString()
        }
    }
    
    /**
     * 解析内容数组
     */
    private fun parseContentArray(contentArray: JsonArray): String {
        return contentArray.joinToString("\n") { element ->
            try {
                if (element is JsonObject) {
                    val type = element["type"]?.jsonPrimitive?.content
                    when (type) {
                        "text" -> {
                            val text = element["text"]?.jsonPrimitive?.content ?: ""
                            // 注意：忽略 cache_control 字段，只提取文本内容
                            // cache_control 是 Claude API 的缓存优化功能，不影响实际内容
                            text
                        }
                        "tool_use" -> {
                            val name = element["name"]?.jsonPrimitive?.content ?: "unknown"
                            val id = element["id"]?.jsonPrimitive?.content ?: "unknown"
                            val input = element["input"]?.toString() ?: "{}"

                            // 避免模型幻觉，使用自然语言描述
                            "I used the $name tool with parameters: $input"
                        }
                        "tool_result" -> {
                            val toolUseId = element["tool_use_id"]?.jsonPrimitive?.content ?: "unknown"
                            val content = element["content"]?.jsonPrimitive?.content ?: ""
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
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse content block: $element" }
                element.toString()
            }
        }
    }
    
    /**
     * 验证请求
     */
    fun validateRequest(request: FlexibleClaudeRequest): List<String> {
        val errors = mutableListOf<String>()
        
        if (request.model.isBlank()) {
            errors.add("Model cannot be empty")
        }
        
        if (request.maxTokens <= 0) {
            errors.add("Max tokens must be positive")
        }
        
        if (request.messages.isEmpty()) {
            errors.add("Messages cannot be empty")
        }
        
        request.messages.forEachIndexed { index, message ->
            if (message.role.isBlank()) {
                errors.add("Message $index: role cannot be empty")
            }
            if (message.content.isBlank()) {
                errors.add("Message $index: content cannot be empty")
            }
        }
        
        return errors
    }
    
    /**
     * 调试信息
     */
    fun toDebugString(request: FlexibleClaudeRequest): String {
        return buildString {
            appendLine("🔍 Flexible Claude Request")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📋 Model: ${request.model}")
            appendLine("🎯 Max Tokens: ${request.maxTokens}")
            appendLine("🌡️  Temperature: ${request.temperature ?: "default"}")
            appendLine("🔄 Stream: ${request.stream}")
            
            request.system?.let { system ->
                appendLine("🔧 System: $system")
            }
            
            appendLine()
            appendLine("💬 Messages:")
            request.messages.forEachIndexed { index, message ->
                appendLine("  ${index + 1}. ${formatRole(message.role)}: ${message.content}")
            }
            
            request.tools?.let { tools ->
                appendLine()
                appendLine("🛠️  Tools: ${tools.size} tool(s) defined")
            }
            
            request.metadata?.let { metadata ->
                appendLine("📋 Metadata: $metadata")
            }
            
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
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
