package com.github.copilot.llmprovider.util

import kotlinx.serialization.json.*
import mu.KotlinLogging

/**
 * Claude 请求解析器 - 处理动态 JSON 结构
 * 避免使用复杂的密封类和自定义序列化器
 */
object ClaudeRequestParser {
    private val logger = KotlinLogging.logger {}
    
    /**
     * 解析的请求数据
     */
    data class ParsedClaudeRequest(
        val model: String,
        val maxTokens: Int,
        val messages: List<ParsedMessage>,
        val system: String? = null,
        val stream: Boolean = false,
        val temperature: Double? = null,
        val topP: Double? = null,
        val topK: Int? = null,
        val tools: List<JsonElement>? = null,
        val toolChoice: JsonElement? = null,
        val stopSequences: List<String>? = null,
        val cacheControl: JsonElement? = null,
        val metadata: JsonElement? = null
    )
    
    /**
     * 解析的消息
     */
    data class ParsedMessage(
        val role: String,
        val content: String,
        val cacheControl: JsonElement? = null
    )
    
    /**
     * 从原始 JSON 字符串解析 Claude 请求
     */
    fun parseRequest(jsonString: String): ParsedClaudeRequest {
        try {
            val jsonElement = Json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject
            
            return ParsedClaudeRequest(
                model = jsonObject["model"]?.jsonPrimitive?.content 
                    ?: throw IllegalArgumentException("Missing required field: model"),
                maxTokens = jsonObject["max_tokens"]?.jsonPrimitive?.int 
                    ?: throw IllegalArgumentException("Missing required field: max_tokens"),
                messages = parseMessages(jsonObject["messages"]?.jsonArray 
                    ?: throw IllegalArgumentException("Missing required field: messages")),
                system = parseSystemField(jsonObject["system"]),
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
            logger.error(e) { "Failed to parse Claude request: $jsonString" }
            throw IllegalArgumentException("Invalid Claude request format: ${e.message}", e)
        }
    }
    
    /**
     * 解析消息数组
     */
    private fun parseMessages(messagesArray: JsonArray): List<ParsedMessage> {
        return messagesArray.map { messageElement ->
            val messageObject = messageElement.jsonObject
            
            ParsedMessage(
                role = messageObject["role"]?.jsonPrimitive?.content 
                    ?: throw IllegalArgumentException("Missing message role"),
                content = parseContentField(messageObject["content"]
                    ?: throw IllegalArgumentException("Missing message content")),
                cacheControl = messageObject["cache_control"]
            )
        }
    }
    
    /**
     * 解析 system 字段（可能是字符串或数组）
     */
    private fun parseSystemField(systemElement: JsonElement?): String? {
        return when (systemElement) {
            null -> null
            is JsonPrimitive -> systemElement.content
            is JsonArray -> parseContentArray(systemElement)
            is JsonObject -> parseContentObject(systemElement)
            else -> systemElement.toString()
        }
    }
    
    /**
     * 解析 content 字段（可能是字符串或数组）
     */
    private fun parseContentField(contentElement: JsonElement): String {
        return when (contentElement) {
            is JsonPrimitive -> contentElement.content
            is JsonArray -> parseContentArray(contentElement)
            is JsonObject -> parseContentObject(contentElement)
            else -> contentElement.toString()
        }
    }
    
    /**
     * 解析内容数组
     */
    private fun parseContentArray(contentArray: JsonArray): String {
        return contentArray.joinToString("\n") { element ->
            when (element) {
                is JsonObject -> parseContentObject(element)
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }
    }
    
    /**
     * 解析内容对象
     */
    private fun parseContentObject(contentObject: JsonObject): String {
        val type = contentObject["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "text" -> contentObject["text"]?.jsonPrimitive?.content ?: ""
            "tool_use" -> {
                val name = contentObject["name"]?.jsonPrimitive?.content ?: "unknown"
                val id = contentObject["id"]?.jsonPrimitive?.content ?: "unknown"
                val input = contentObject["input"]?.toString() ?: "{}"

                // 为了避免模型产生幻觉，我们将工具调用转换为更自然的描述
                // 而不是使用可能被模型模仿的 [Tool: xxx] 格式
                "I used the $name tool with parameters: $input"
            }
            "tool_result" -> {
                val toolUseId = contentObject["tool_use_id"]?.jsonPrimitive?.content ?: "unknown"
                val content = contentObject["content"]?.jsonPrimitive?.content ?: ""
                if (content.isNotBlank()) {
                    // 将工具结果转换为更自然的描述
                    "The tool execution returned: $content"
                } else {
                    "The tool execution completed."
                }
            }
            else -> {
                // 如果不是标准的内容块，尝试提取文本
                contentObject["text"]?.jsonPrimitive?.content 
                    ?: contentObject["content"]?.jsonPrimitive?.content
                    ?: "[${type ?: "unknown"}]"
            }
        }
    }
    
    /**
     * 验证请求格式
     */
    fun validateRequest(request: ParsedClaudeRequest): List<String> {
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
     * 将解析的请求转换为调试字符串
     */
    fun toDebugString(request: ParsedClaudeRequest): String {
        return buildString {
            appendLine("🔍 Parsed Claude Request")
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
