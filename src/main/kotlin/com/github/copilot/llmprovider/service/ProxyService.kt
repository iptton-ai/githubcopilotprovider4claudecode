package com.github.copilot.llmprovider.service

import com.github.copilot.llmprovider.exception.TokenExpiredException
import com.github.copilot.llmprovider.exception.RateLimitException
import com.github.copilot.llmprovider.model.*
import com.github.copilot.llmprovider.util.FlexibleClaudeParser
import com.github.copilot.llmprovider.util.RequestResponseLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import mu.KotlinLogging

/**
 * 代理服务接口
 * 负责将请求转发到目标 API 服务器
 */
interface ProxyService {
    
    /**
     * 转发 OpenAI 聊天完成请求
     */
    suspend fun forwardOpenAIRequest(request: OpenAIChatCompletionRequest): OpenAIChatCompletionResponse
    
    /**
     * 转发 OpenAI 流式聊天完成请求
     */
    suspend fun forwardOpenAIStreamRequest(request: OpenAIChatCompletionRequest): Flow<String>
    
    /**
     * 转发 Claude 消息请求
     */
    suspend fun forwardClaudeRequest(request: FlexibleClaudeParser.FlexibleClaudeRequest): ClaudeMessageResponse
    
    /**
     * 转发 Claude 流式消息请求
     */
    suspend fun forwardClaudeStreamRequest(request: FlexibleClaudeParser.FlexibleClaudeRequest): Flow<String>
}

/**
 * 代理服务实现
 */
class ProxyServiceImpl(
    private val authManager: com.github.copilot.llmprovider.auth.AuthManager
) : ProxyService {

    // JSON 序列化实例
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        encodeDefaults = false
    }

    // 会话状态：记录当前会话使用的降级模型
    @Volatile
    private var sessionFallbackModel: String? = null

    // 会话状态：记录降级模型的使用时间，用于可能的重置逻辑
    @Volatile
    private var fallbackModelSetTime: Long = 0

    override suspend fun forwardOpenAIRequest(request: OpenAIChatCompletionRequest): OpenAIChatCompletionResponse {
        // 记录请求
        RequestResponseLogger.logOpenAIRequest(request)

        // 选择合适的模型
        val initialModel = selectBestModel(request.model)

        return executeWithRetryAndFallback(originalModel = initialModel) { apiToken, model ->
            val copilotService = authManager.getCopilotService()

            // 转换 OpenAI 请求格式为 GitHub Copilot API 格式
            val messages = request.messages.map { message ->
                mapOf(
                    "role" to message.role,
                    "content" to (message.content ?: "")
                )
            }

            // 简化工具日志
            if (request.tools?.isNotEmpty() == true) {
                println("🛠️  Tools: ${request.tools.size} available")
            }

            val response = copilotService.sendChatCompletion(
                apiToken = apiToken,
                model = model,
                messages = messages,
                stream = false,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                tools = request.tools,
                toolChoice = request.toolChoice
            )

            // 记录响应
            RequestResponseLogger.logOpenAIResponse(response)
            response
        }
    }

    override suspend fun forwardOpenAIStreamRequest(request: OpenAIChatCompletionRequest): Flow<String> {
        return flow {
            // 选择合适的模型
            val initialModel = selectBestModel(request.model)

            val result = executeWithRetryAndFallback(originalModel = initialModel) { apiToken, model ->
                val copilotService = authManager.getCopilotService()

                // 转换 OpenAI 请求格式
                val messages = request.messages.map { message ->
                    mapOf(
                        "role" to message.role,
                        "content" to (message.content ?: "")
                    )
                }

                copilotService.sendStreamingChatCompletion(
                    apiToken = apiToken,
                    model = model,
                    messages = messages,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    tools = request.tools,
                    toolChoice = request.toolChoice
                )
            }

            // 发出流中的所有元素
            result.collect { chunk ->
                emit(chunk)
            }
        }
    }

    override suspend fun forwardClaudeRequest(request: FlexibleClaudeParser.FlexibleClaudeRequest): ClaudeMessageResponse {
        // 记录 Claude 请求
        println(FlexibleClaudeParser.toDebugString(request))

        try {
            // 将 Claude 请求转换为 OpenAI 格式，然后转发
            val openAIRequest = convertClaudeToOpenAI(request)

            // 调试：打印转换后的 OpenAI 请求
            println("\n🔄 Converted OpenAI Request:")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📋 Model: ${openAIRequest.model}")
            println("🎯 Max Tokens: ${openAIRequest.maxTokens}")
            println("🌡️  Temperature: ${openAIRequest.temperature}")
            println("🔄 Stream: ${openAIRequest.stream}")
            println("💬 Messages:")
            openAIRequest.messages.forEachIndexed { index, message ->
                println("  ${index + 1}. ${message.role}: ${message.content?.take(100)}${if ((message.content?.length ?: 0) > 100) "..." else ""}")
            }
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val openAIResponse = forwardOpenAIRequest(openAIRequest)

            // 将 OpenAI 响应转换回 Claude 格式
            val claudeResponse = convertOpenAIToClaudeResponse(openAIResponse, request.model)

            // 记录 Claude 响应
            RequestResponseLogger.logClaudeResponse(claudeResponse)

            return claudeResponse
        } catch (e: Exception) {
            RequestResponseLogger.logError("Claude request failed", e.message)
            throw e
        }
    }

    override suspend fun forwardClaudeStreamRequest(request: FlexibleClaudeParser.FlexibleClaudeRequest): Flow<String> {
        // 将 Claude 请求转换为 OpenAI 格式，然后转发
        val openAIRequest = convertClaudeToOpenAI(request)
        return forwardOpenAIStreamRequest(openAIRequest)
    }

    /**
     * 选择最佳模型（初始选择，不涉及 API 调用）
     * 如果会话中已经有降级模型，优先使用降级模型
     */
    private fun selectBestModel(requestedModel: String): String {
        // 如果会话中已经设置了降级模型，优先使用降级模型
        sessionFallbackModel?.let { fallbackModel ->
            return fallbackModel
        }

        return when {
            requestedModel.contains("claude", ignoreCase = true) -> {
                // 如果请求 Claude 模型，返回请求的模型或默认的 Claude 4
                if (requestedModel.contains("claude-3.7", ignoreCase = true)) {
                    "claude-3.7-sonnet"
                } else if (requestedModel.contains("claude-3.5", ignoreCase = true)) {
                    "claude-3.5-sonnet"
                } else {
                    "claude-sonnet-4" // 默认使用 Claude 4
                }
            }
            requestedModel.contains("gpt", ignoreCase = true) -> {
                // 如果请求 GPT 模型，尝试映射到可用的模型
                when {
                    requestedModel.contains("gpt-4") -> "gpt-4o"
                    requestedModel.contains("gpt-3.5") -> "gpt-3.5-turbo"
                    else -> requestedModel
                }
            }
            else -> {
                // 默认使用 Claude 4
                "claude-sonnet-4"
            }
        }
    }

    /**
     * 获取实际可用的最佳模型（涉及 API 调用）
     */
    private suspend fun getActualBestModel(requestedModel: String, apiToken: String): String {
        return when {
            requestedModel.contains("claude", ignoreCase = true) -> {
                // 如果请求 Claude 模型，使用首选的 Claude 模型
                authManager.getCopilotService().getPreferredClaudeModel(apiToken)
            }
            else -> {
                // 对于其他模型，使用初始选择
                selectBestModel(requestedModel)
            }
        }
    }

    /**
     * 将 Claude 请求转换为 OpenAI 格式
     */
    private fun convertClaudeToOpenAI(request: FlexibleClaudeParser.FlexibleClaudeRequest): OpenAIChatCompletionRequest {
        val messages = mutableListOf<OpenAIMessage>()

        // 添加系统消息（如果有）
        request.system?.let { systemText ->
            if (systemText.isNotBlank()) {
                messages.add(OpenAIMessage(role = "system", content = systemText))
            }
        }

        // 转换用户和助手消息
        request.messages.forEach { claudeMessage ->
            val openAIMessage = convertClaudeMessageToOpenAI(claudeMessage)
            messages.add(openAIMessage)
        }

        // 确保至少有一条消息
        if (messages.isEmpty()) {
            messages.add(OpenAIMessage(role = "user", content = "Hello"))
        }

        // 确保 maxTokens 有效
        val validMaxTokens = when {
            request.maxTokens <= 0 -> 100
            request.maxTokens > 4096 -> 4096
            else -> request.maxTokens
        }

        // 转换 tools（如果有）
        val openAITools = request.tools?.mapNotNull { toolElement ->
            try {
                val toolObj = toolElement.jsonObject

                // 检查是否是 Claude API 格式 (有 name 和 input_schema)
                val claudeName = toolObj["name"]?.jsonPrimitive?.content
                val claudeInputSchema = toolObj["input_schema"]?.jsonObject

                if (claudeName != null && claudeInputSchema != null) {
                    // Claude API 格式转换为 OpenAI 格式

                    val description = toolObj["description"]?.jsonPrimitive?.content ?: ""

                    // 转换 input_schema 为 parameters
                    val parameters = buildJsonObject {
                        put("type", claudeInputSchema["type"] ?: JsonPrimitive("object"))
                        claudeInputSchema["properties"]?.let { put("properties", it) }
                        claudeInputSchema["required"]?.let { put("required", it) }
                        // 忽略 additionalProperties 和 $schema，OpenAI 不需要
                    }

                    OpenAITool(
                        type = "function",
                        function = OpenAIFunctionDefinition(
                            name = claudeName,
                            description = description,
                            parameters = parameters
                        )
                    )
                } else {
                    // 检查是否是 OpenAI API 格式 (有 type 和 function)
                    val type = toolObj["type"]?.jsonPrimitive?.content ?: "function"
                    val functionObj = toolObj["function"]?.jsonObject

                    if (functionObj != null) {
                        val name = functionObj["name"]?.jsonPrimitive?.content ?: ""
                        val description = functionObj["description"]?.jsonPrimitive?.content
                        val parameters = functionObj["parameters"]

                        OpenAITool(
                            type = type,
                            function = OpenAIFunctionDefinition(
                                name = name,
                                description = description,
                                parameters = parameters
                            )
                        )
                    } else {
                        logger.warn { "Tool missing both Claude and OpenAI format fields: $toolElement" }
                        null
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to parse tool: ${toolElement}, error: ${e.message}" }
                null
            }
        }

        return OpenAIChatCompletionRequest(
            model = request.model,
            messages = messages,
            stream = request.stream,
            temperature = request.temperature?.takeIf { it in 0.0..2.0 },
            maxTokens = validMaxTokens,
            tools = openAITools,
            toolChoice = request.toolChoice
        )
    }

    /**
     * 将单个 Claude 消息转换为 OpenAI 格式
     * 保持工具调用的结构化格式，避免转换为文本
     */
    private fun convertClaudeMessageToOpenAI(claudeMessage: FlexibleClaudeParser.FlexibleMessage): OpenAIMessage {
        // 尝试解析原始内容为 JSON 数组（结构化格式）
        val originalContent = claudeMessage.originalContent
        if (originalContent != null) {
            try {
                val contentArray = json.parseToJsonElement(originalContent) as? JsonArray
                if (contentArray != null) {
                    return convertStructuredClaudeMessage(claudeMessage.role, contentArray)
                }
            } catch (e: Exception) {
                // 如果解析失败，继续使用文本格式
            }
        }

        // 回退到文本格式
        val content = claudeMessage.content.takeIf { it.isNotBlank() } ?: "Hello"
        return OpenAIMessage(
            role = claudeMessage.role,
            content = content
        )
    }

    /**
     * 转换结构化的 Claude 消息（包含 tool_use 和 tool_result）
     */
    private fun convertStructuredClaudeMessage(role: String, contentArray: JsonArray): OpenAIMessage {
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<OpenAIToolCall>()
        var toolCallId: String? = null

        contentArray.forEach { element ->
            if (element is JsonObject) {
                val type = element["type"]?.jsonPrimitive?.content
                when (type) {
                    "text" -> {
                        val text = element["text"]?.jsonPrimitive?.content
                        if (!text.isNullOrBlank()) {
                            textParts.add(text)
                        }
                    }
                    "tool_use" -> {
                        val id = element["id"]?.jsonPrimitive?.content ?: "unknown"
                        val name = element["name"]?.jsonPrimitive?.content ?: "unknown"
                        val input = element["input"]?.toString() ?: "{}"

                        toolCalls.add(OpenAIToolCall(
                            id = id,
                            type = "function",
                            function = OpenAIFunction(
                                name = name,
                                arguments = input
                            )
                        ))
                    }
                    "tool_result" -> {
                        // 对于 tool_result，我们需要设置 tool_call_id
                        toolCallId = element["tool_use_id"]?.jsonPrimitive?.content
                        val content = element["content"]?.jsonPrimitive?.content
                        if (!content.isNullOrBlank()) {
                            textParts.add(content)
                        }
                    }
                }
            }
        }

        // 构建 OpenAI 消息
        val content = if (textParts.isNotEmpty()) textParts.joinToString("\n") else null

        return OpenAIMessage(
            role = role,
            content = content,
            toolCalls = if (toolCalls.isNotEmpty()) toolCalls else null,
            toolCallId = toolCallId
        )
    }

    /**
     * 将 OpenAI 响应转换为 Claude 格式
     */
    private fun convertOpenAIToClaudeResponse(
        response: OpenAIChatCompletionResponse,
        originalModel: String
    ): ClaudeMessageResponse {
        // 构建 Claude 内容块
        val contentBlocks = mutableListOf<ClaudeContentBlock>()
        var hasToolCalls = false

        // 处理所有 choices，GitHub Copilot 可能返回多个 choices
        response.choices.forEach { choice ->
            val message = choice.message

            // GitHub Copilot 响应处理

            // 添加文本内容（如果有）
            val textContent = message?.content
            if (!textContent.isNullOrBlank()) {
                contentBlocks.add(ClaudeContentBlock.text(textContent))
            }

            // 添加工具调用（如果有）
            message?.toolCalls?.forEach { toolCall ->
                hasToolCalls = true

                // 解析 arguments JSON 字符串为 JsonElement
                val input = try {
                    json.parseToJsonElement(toolCall.function.arguments)
                } catch (e: Exception) {
                    logger.warn { "Failed to parse tool arguments: ${toolCall.function.arguments}, error: ${e.message}" }
                    // 如果解析失败，创建一个包含原始字符串的对象
                    buildJsonObject {
                        put("arguments", toolCall.function.arguments)
                    }
                }

                contentBlocks.add(
                    ClaudeContentBlock.toolUse(
                        id = toolCall.id,
                        name = toolCall.function.name,
                        input = input
                    )
                )
            }
        }

        // 如果没有任何内容，添加一个空的文本块
        if (contentBlocks.isEmpty()) {
            contentBlocks.add(ClaudeContentBlock.text(""))
        }

        // 确定停止原因，优先检查是否有工具调用
        val stopReason = if (hasToolCalls) {
            "tool_use"
        } else {
            when (response.choices.firstOrNull()?.finishReason) {
                "stop" -> "end_turn"
                "length" -> "max_tokens"
                else -> "end_turn"
            }
        }



        return ClaudeMessageResponse(
            id = response.id,
            type = "message",
            role = "assistant",
            content = contentBlocks,
            model = originalModel,
            stopReason = stopReason,
            stopSequence = null,
            usage = ClaudeUsage(
                inputTokens = response.usage?.promptTokens ?: 0,
                outputTokens = response.usage?.completionTokens ?: 0
            )
        )
    }

    /**
     * 执行操作，如果遇到 token 过期则自动重试
     */
    private suspend fun <T> executeWithTokenRetry(
        maxRetries: Int = 1,
        operation: suspend (apiToken: String) -> T
    ): T {
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val apiToken = if (attempt == 0) {
                    // 第一次尝试：使用缓存的 token
                    authManager.getValidApiToken()
                } else {
                    // 重试：强制刷新 token
                    authManager.forceRefreshApiToken()
                }

                return operation(apiToken)

            } catch (e: TokenExpiredException) {
                logger.warn { "Token expired on attempt ${attempt + 1}: ${e.message}" }
                lastException = e

                if (attempt >= maxRetries) {
                    logger.error { "Max retries ($maxRetries) exceeded for token refresh" }
                    break
                }

                // 继续重试
            } catch (e: Exception) {
                // 其他异常直接抛出，不重试
                logger.error(e) { "Non-token error occurred: ${e.message}" }
                throw e
            }
        }

        // 如果所有重试都失败了，抛出最后的异常
        throw lastException ?: Exception("Unknown error in token retry logic")
    }

    /**
     * 执行操作，支持 token 重试和模型降级
     */
    private suspend fun <T> executeWithRetryAndFallback(
        maxRetries: Int = 1,
        originalModel: String,
        operation: suspend (apiToken: String, model: String) -> T
    ): T {
        var lastException: Exception? = null
        var currentModel = originalModel
        var isFirstAttempt = true

        for (attempt in 0..maxRetries) {
            try {
                val apiToken = if (attempt == 0) {
                    // 第一次尝试：使用缓存的 token
                    authManager.getValidApiToken()
                } else {
                    // 重试：强制刷新 token
                    authManager.forceRefreshApiToken()
                }

                // 第一次尝试时，获取实际可用的最佳模型
                if (isFirstAttempt) {
                    currentModel = getActualBestModel(originalModel, apiToken)
                    isFirstAttempt = false
                }

                return operation(apiToken, currentModel)

            } catch (e: TokenExpiredException) {
                logger.warn { "Token expired on attempt ${attempt + 1}: ${e.message}" }
                lastException = e

                if (attempt >= maxRetries) {
                    logger.error { "Max retries ($maxRetries) exceeded for token refresh" }
                    break
                }

                // 继续重试
            } catch (e: RateLimitException) {
                logger.warn { "Rate limit exceeded with model $currentModel: ${e.message}" }

                // 尝试获取降级模型
                try {
                    val apiToken = authManager.getValidApiToken()
                    val fallbackModel = authManager.getCopilotService().getFallbackModelForRateLimit(apiToken, currentModel)

                    if (fallbackModel != currentModel) {
                        currentModel = fallbackModel

                        // 记录降级模型到会话状态
                        setSessionFallbackModel(fallbackModel)

                        // 用降级模型重试一次
                        try {
                            return operation(apiToken, currentModel)
                        } catch (e2: RateLimitException) {
                            logger.error { "Rate limit still exceeded even with fallback model $currentModel" }
                            throw e2
                        }
                    } else {
                        logger.error { "No suitable fallback model found for rate limit" }
                        throw e
                    }
                } catch (fallbackException: Exception) {
                    logger.error(fallbackException) { "Failed to get fallback model" }
                    throw e
                }
            } catch (e: Exception) {
                // 其他异常直接抛出，不重试
                logger.error(e) { "Non-recoverable error occurred: ${e.message}" }
                throw e
            }
        }

        // 如果所有重试都失败了，抛出最后的异常
        throw lastException ?: Exception("Unknown error in retry logic")
    }

    /**
     * 设置会话降级模型
     */
    private fun setSessionFallbackModel(fallbackModel: String) {
        sessionFallbackModel = fallbackModel
        fallbackModelSetTime = System.currentTimeMillis()
        println("🔄 Using fallback model: $fallbackModel")
    }

    /**
     * 清除会话降级模型（可用于重置会话状态）
     */
    fun clearSessionFallbackModel() {
        sessionFallbackModel = null
        fallbackModelSetTime = 0
    }

    /**
     * 获取当前会话的降级模型
     */
    fun getSessionFallbackModel(): String? = sessionFallbackModel

    /**
     * 检查是否有活跃的会话降级模型
     */
    fun hasSessionFallbackModel(): Boolean = sessionFallbackModel != null

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
