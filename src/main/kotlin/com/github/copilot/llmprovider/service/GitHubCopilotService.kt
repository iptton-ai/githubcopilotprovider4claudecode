package com.github.copilot.llmprovider.service

import com.github.copilot.llmprovider.exception.TokenExpiredException
import com.github.copilot.llmprovider.exception.RateLimitException
import com.github.copilot.llmprovider.model.OpenAIChatCompletionResponse
import com.github.copilot.llmprovider.model.OpenAITool
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * GitHub Copilot API 服务
 */
class GitHubCopilotService(
    private val httpClientEngine: HttpClientEngine? = null
) {
    @Volatile
    private var cachedApiEndpoint: String = COPILOT_INDIVIDUAL_API_BASE

    // JSON 序列化实例
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        encodeDefaults = false
    }
    companion object {
        const val GITHUB_API_BASE = "https://api.github.com"
        const val COPILOT_API_BASE = "https://api.githubcopilot.com"
        const val COPILOT_INDIVIDUAL_API_BASE = "https://api.individual.githubcopilot.com"
        
        // 首选模型列表（按优先级排序）
        val PREFERRED_CLAUDE_MODELS = listOf(
            "claude-sonnet-4",
            "claude-3.7-sonnet",
            "claude-3.5-sonnet",
            "claude-3-sonnet-20240229",
            "claude-3-haiku"
        )

        // 降级模型（当遇到 429 错误时使用）
        const val FALLBACK_MODEL_FOR_RATE_LIMIT = "gpt-4o"

        /**
         * 转义 JSON 字符串中的特殊字符
         */
        private fun escapeJsonString(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\u000C", "\\f")
        }

        /**
         * 检查错误响应是否可能与 token 相关
         */
        private fun isTokenRelatedError(errorBody: String): Boolean {
            val lowerErrorBody = errorBody.lowercase()

            // 检查常见的 token 过期相关错误信息
            val tokenRelatedKeywords = listOf(
                "request timeout has expired",  // 你提到的具体错误
                "timeout",
                "expired",
                "unauthorized",
                "authentication",
                "invalid token",
                "token expired",
                "access denied",
                "forbidden",
                "credential"
            )

            return tokenRelatedKeywords.any { keyword ->
                lowerErrorBody.contains(keyword)
            }
        }

        /**
         * 检查错误响应是否是速率限制相关
         */
        private fun isRateLimitError(errorBody: String): Boolean {
            val lowerErrorBody = errorBody.lowercase()

            // 检查常见的速率限制相关错误信息
            val rateLimitKeywords = listOf(
                "rate limit exceeded",
                "quota exceeded",
                "too many requests",
                "429",
                "rate limiting",
                "throttled",
                "quota limit",
                "usage limit"
            )

            return rateLimitKeywords.any { keyword ->
                lowerErrorBody.contains(keyword)
            }
        }
    }

    private val httpClient = if (httpClientEngine != null) {
        HttpClient(httpClientEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                    encodeDefaults = false
                })
            }
        }
    } else {
        HttpClient(CIO) {
            engine {
                requestTimeout = 10 * 60 * 1000L // 10 分钟
                endpoint {
                    connectTimeout = 30 * 1000L // 30 秒连接超时
                    socketTimeout = 10 * 60 * 1000L // 10 分钟 socket 超时
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                    encodeDefaults = false
                })
            }
        }
    }

    init {
        // Service initialized with extended timeouts
    }

    /**
     * 使用 OAuth token 获取 API token
     */
    suspend fun getApiToken(oauthToken: String): CopilotApiToken {

        val response = httpClient.get("$GITHUB_API_BASE/copilot_internal/v2/token") {
            headers {
                append(HttpHeaders.Authorization, "token $oauthToken")
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
            }
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to get API token: ${response.status} - $errorBody" }
            throw Exception("Failed to get Copilot API token: ${response.status}")
        }

        val apiToken = response.body<CopilotApiToken>()

        // 缓存 API 端点信息
        cachedApiEndpoint = apiToken.endpoints?.api ?: COPILOT_INDIVIDUAL_API_BASE

        return apiToken
    }

    /**
     * 获取支持的模型列表
     */
    suspend fun getSupportedModels(apiToken: String): ModelsResponse {
        
        val response = httpClient.get("$cachedApiEndpoint/models") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiToken")
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
                append("Editor-Version", "vscode/1.95.0")
                append("Editor-Plugin-Version", "copilot/1.0.0")
            }
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error { "Failed to get models: ${response.status} - $errorBody" }
            throw Exception("Failed to get supported models: ${response.status}")
        }

        val models = response.body<ModelsResponse>()

        // 简化模型列表显示
        println("🤖 Models: ${models.data.size} available")
        
        return models
    }

    /**
     * 获取首选的 Claude 模型
     */
    suspend fun getPreferredClaudeModel(apiToken: String): String {
        val models = getSupportedModels(apiToken)
        val availableModels = models.data.map { it.id }

        // 按优先级查找可用的 Claude 模型
        for (preferredModel in PREFERRED_CLAUDE_MODELS) {
            if (availableModels.contains(preferredModel)) {
                logger.info { "Using preferred Claude model: $preferredModel" }
                return preferredModel
            }
        }

        // 如果没有找到首选模型，查找任何 Claude 模型
        val claudeModel = availableModels.find { it.contains("claude", ignoreCase = true) }
        if (claudeModel != null) {
            logger.info { "Using available Claude model: $claudeModel" }
            return claudeModel
        }

        // 如果没有 Claude 模型，使用第一个可用模型
        val fallbackModel = availableModels.firstOrNull() ?: "gpt-4o"
        logger.warn { "No Claude model found, using fallback: $fallbackModel" }
        return fallbackModel
    }

    /**
     * 获取降级模型（当遇到 429 错误时使用）
     */
    suspend fun getFallbackModelForRateLimit(apiToken: String, currentModel: String): String {
        val models = getSupportedModels(apiToken)
        val availableModels = models.data.map { it.id }

        // 首先检查是否有指定的降级模型
        if (availableModels.contains(FALLBACK_MODEL_FOR_RATE_LIMIT)) {
            logger.info { "Using fallback model for rate limit: $FALLBACK_MODEL_FOR_RATE_LIMIT" }
            return FALLBACK_MODEL_FOR_RATE_LIMIT
        }

        // 如果当前模型是 Claude 4，尝试降级到 Claude 3.7 或 3.5
        // if (currentModel.contains("claude-sonnet-4", ignoreCase = true)) {
        //     val fallbackOptions = listOf("claude-3.7-sonnet", "claude-3.5-sonnet", "claude-3-sonnet-20240229")
        //     for (fallback in fallbackOptions) {
        //         if (availableModels.contains(fallback)) {
        //             logger.info { "Downgrading from $currentModel to $fallback due to rate limit" }
        //             return fallback
        //         }
        //     }
        // }

        // 如果当前模型是 Claude 3.7，尝试降级到 3.5 或更低
        // if (currentModel.contains("claude-3.7", ignoreCase = true)) {
        //     val fallbackOptions = listOf("claude-3.5-sonnet", "claude-3-sonnet-20240229", "claude-3-haiku")
        //     for (fallback in fallbackOptions) {
        //         if (availableModels.contains(fallback)) {
        //             logger.info { "Downgrading from $currentModel to $fallback due to rate limit" }
        //             return fallback
        //         }
        //     }
        // }

        // 查找任何可用的 Claude 模型（除了当前模型）
        // val claudeModel = availableModels.find {
        //     it.contains("claude", ignoreCase = true) && it != currentModel
        // }
        // if (claudeModel != null) {
        //     logger.info { "Using alternative Claude model: $claudeModel" }
        //     return claudeModel
        // }

        // 最后的降级选择：使用 GPT 模型
        val gptModel = availableModels.find { it.contains("gpt", ignoreCase = true) }
        if (gptModel != null) {
            logger.warn { "No alternative Claude model found, falling back to GPT: $gptModel" }
            return gptModel
        }

        // 如果都没有，返回当前模型（可能会继续失败，但至少不会崩溃）
        logger.error { "No fallback model found, keeping current model: $currentModel" }
        return currentModel
    }

    /**
     * 发送聊天完成请求
     */
    suspend fun sendChatCompletion(
        apiToken: String,
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean = false,
        temperature: Double? = null,
        maxTokens: Int? = null,
        tools: List<OpenAITool>? = null,
        toolChoice: JsonElement? = null
    ): OpenAIChatCompletionResponse {
        
        // 构建 JSON，正确处理字符串转义
        val requestBodyJson = buildString {
            append("{")
            append("\"model\":\"${escapeJsonString(model)}\",")
            append("\"messages\":[")
            messages.forEachIndexed { index, message ->
                if (index > 0) append(",")
                append("{")
                append("\"role\":\"${escapeJsonString(message["role"]?.toString() ?: "")}\",")
                append("\"content\":\"${escapeJsonString(message["content"]?.toString() ?: "")}\"")
                append("}")
            }
            append("],\"stream\":$stream")
            temperature?.let { append(",\"temperature\":$it") }
            maxTokens?.let { append(",\"max_tokens\":$it") }

            // 添加 tools（如果有）
            tools?.let { toolsList ->
                if (toolsList.isNotEmpty()) {
                    append(",\"tools\":[")
                    toolsList.forEachIndexed { index, tool ->
                        if (index > 0) append(",")
                        // 手动构建 tool JSON
                        append("{\"type\":\"${escapeJsonString(tool.type)}\",")
                        append("\"function\":{")
                        append("\"name\":\"${escapeJsonString(tool.function.name)}\",")
                        append("\"description\":\"${escapeJsonString(tool.function.description ?: "")}\",")
                        append("\"parameters\":")
                        append(tool.function.parameters?.toString() ?: "{}")
                        append("}}")
                    }
                    append("]")

                    // 添加 tool_choice（如果有）
                    toolChoice?.let { choice ->
                        append(",\"tool_choice\":")
                        append(choice.toString())
                    }
                }
            }

            append("}")
        }

        // 简化请求日志
        println("📤 → GitHub Copilot API")

        val response = httpClient.post("$cachedApiEndpoint/chat/completions") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiToken")
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
                append("Editor-Version", "vscode/1.95.0")
                append("Editor-Plugin-Version", "copilot/1.0.0")
            }
            setBody(requestBodyJson)
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error { "Chat completion failed: ${response.status} - $errorBody" }

            // 检查是否是认证错误（token 过期）
            if (response.status == HttpStatusCode.Unauthorized) {
                logger.warn { "API token appears to be expired or invalid (401)" }
                throw TokenExpiredException(
                    "GitHub Copilot API token expired or invalid",
                    statusCode = response.status.value
                )
            }

            // 检查是否是速率限制错误 (429 Too Many Requests)
            if (response.status == HttpStatusCode.TooManyRequests) {
                logger.warn { "Rate limit exceeded (429) - model may need to be downgraded" }

                // 尝试从响应头中获取 Retry-After
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()

                throw RateLimitException(
                    "Rate limit exceeded: $errorBody",
                    statusCode = response.status.value,
                    retryAfter = retryAfter
                )
            }

            // 检查 500 错误是否可能是 token 过期导致的
            if (response.status == HttpStatusCode.InternalServerError) {
                if (isTokenRelatedError(errorBody)) {
                    logger.warn { "API token appears to be expired or invalid (500 with token-related error)" }
                    throw TokenExpiredException(
                        "GitHub Copilot API token expired or invalid (detected from 500 error)",
                        statusCode = response.status.value
                    )
                }

                // 检查 500 错误是否包含速率限制相关信息
                if (isRateLimitError(errorBody)) {
                    logger.warn { "Rate limit detected in 500 error response" }
                    throw RateLimitException(
                        "Rate limit exceeded (detected from 500 error): $errorBody",
                        statusCode = 500
                    )
                }
            }

            throw Exception("Chat completion failed: ${response.status}")
        }

        // 获取原始响应文本
        val responseText = response.bodyAsText()

        // 简化响应日志
        println("📥 ← GitHub Copilot API (${response.status})")

        val completionResponse = try {
            json.decodeFromString<OpenAIChatCompletionResponse>(responseText)
        } catch (e: Exception) {
            println("❌ JSON parsing error: ${e.message}")
            throw e
        }

        return completionResponse
    }

    /**
     * 发送流式聊天完成请求
     */
    suspend fun sendStreamingChatCompletion(
        apiToken: String,
        model: String,
        messages: List<Map<String, Any>>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        tools: List<OpenAITool>? = null,
        toolChoice: JsonElement? = null
    ): Flow<String> = flow {
        
        // 构建 JSON，正确处理字符串转义
        val requestBodyJson = buildString {
            append("{")
            append("\"model\":\"${escapeJsonString(model)}\",")
            append("\"messages\":[")
            messages.forEachIndexed { index, message ->
                if (index > 0) append(",")
                append("{")
                append("\"role\":\"${escapeJsonString(message["role"]?.toString() ?: "")}\",")
                append("\"content\":\"${escapeJsonString(message["content"]?.toString() ?: "")}\"")
                append("}")
            }
            append("],\"stream\":true")
            temperature?.let { append(",\"temperature\":$it") }
            maxTokens?.let { append(",\"max_tokens\":$it") }

            // 添加 tools（如果有）
            tools?.let { toolsList ->
                if (toolsList.isNotEmpty()) {
                    append(",\"tools\":[")
                    toolsList.forEachIndexed { index, tool ->
                        if (index > 0) append(",")
                        // 手动构建 tool JSON
                        append("{\"type\":\"${escapeJsonString(tool.type)}\",")
                        append("\"function\":{")
                        append("\"name\":\"${escapeJsonString(tool.function.name)}\",")
                        append("\"description\":\"${escapeJsonString(tool.function.description ?: "")}\",")
                        append("\"parameters\":")
                        append(tool.function.parameters?.toString() ?: "{}")
                        append("}}")

                    }
                    append("]")

                    // 添加 tool_choice（如果有）
                    toolChoice?.let { choice ->
                        append(",\"tool_choice\":")
                        append(choice.toString())
                    }
                }
            }

            append("}")
        }

        // 简化流式请求日志
        println("📤 → GitHub Copilot API (streaming)")

        val response = httpClient.post("$cachedApiEndpoint/chat/completions") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiToken")
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.Accept, "text/event-stream")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
                append("Editor-Version", "vscode/1.95.0")
                append("Editor-Plugin-Version", "copilot/1.0.0")
            }
            setBody(requestBodyJson)
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error { "Streaming chat completion failed: ${response.status} - $errorBody" }

            // 检查是否是认证错误（token 过期）
            if (response.status == HttpStatusCode.Unauthorized) {
                logger.warn { "API token appears to be expired or invalid (streaming 401)" }
                throw TokenExpiredException(
                    "GitHub Copilot API token expired or invalid (streaming)",
                    statusCode = response.status.value
                )
            }

            // 检查是否是速率限制错误 (429 Too Many Requests)
            if (response.status == HttpStatusCode.TooManyRequests) {
                logger.warn { "Rate limit exceeded (streaming 429) - model may need to be downgraded" }

                // 尝试从响应头中获取 Retry-After
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()

                throw RateLimitException(
                    "Rate limit exceeded (streaming): $errorBody",
                    statusCode = response.status.value,
                    retryAfter = retryAfter
                )
            }

            // 检查 500 错误是否可能是 token 过期导致的
            if (response.status == HttpStatusCode.InternalServerError) {
                if (isTokenRelatedError(errorBody)) {
                    logger.warn { "API token appears to be expired or invalid (streaming 500 with token-related error)" }
                    throw TokenExpiredException(
                        "GitHub Copilot API token expired or invalid (streaming, detected from 500 error)",
                        statusCode = response.status.value
                    )
                }

                // 检查 500 错误是否包含速率限制相关信息
                if (isRateLimitError(errorBody)) {
                    logger.warn { "Rate limit detected in streaming 500 error response" }
                    throw RateLimitException(
                        "Rate limit exceeded (streaming, detected from 500 error): $errorBody",
                        statusCode = 500
                    )
                }
            }

            throw Exception("Streaming chat completion failed: ${response.status}")
        }

        // 处理 SSE 流
        val responseText = response.bodyAsText()
        val lines = responseText.split("\n")
        
        for (line in lines) {
            if (line.startsWith("data: ") && line != "data: [DONE]") {
                val data = line.substring(6) // 移除 "data: " 前缀
                emit(data)
            }
        }
    }

    /**
     * 检查 API token 是否过期
     */
    fun isTokenExpired(apiToken: CopilotApiToken): Boolean {
        val currentTime = System.currentTimeMillis() / 1000
        return currentTime >= apiToken.expiresAt
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
data class CopilotApiToken(
    val token: String,
    @SerialName("expires_at")
    val expiresAt: Long,
    @SerialName("refresh_in")
    val refreshIn: Int,
    @SerialName("annotations_enabled")
    val annotationsEnabled: Boolean? = null,
    @SerialName("chat_enabled")
    val chatEnabled: Boolean? = null,
    val endpoints: CopilotEndpoints? = null,
    val individual: Boolean? = null,
    val sku: String? = null,
    @SerialName("tracking_id")
    val trackingId: String? = null
)

@Serializable
data class CopilotEndpoints(
    val api: String? = null,
    @SerialName("origin-tracker")
    val originTracker: String? = null,
    val proxy: String? = null,
    val telemetry: String? = null
)

@Serializable
data class ModelsResponse(
    val data: List<ModelInfo>,
    val `object`: String? = null
)

@Serializable
data class ModelInfo(
    val id: String,
    val `object`: String? = null,
    val created: Long? = null,
    @SerialName("owned_by")
    val ownedBy: String? = null,
    val capabilities: ModelCapabilities? = null
)

@Serializable
data class ModelCapabilities(
    val family: String? = null,
    val limits: ModelLimits? = null,
    val `object`: String? = null,
    val supports: ModelSupports? = null,
    val tokenizer: String? = null,
    val type: String? = null
)

@Serializable
data class ModelLimits(
    @SerialName("max_context_window_tokens")
    val maxContextWindowTokens: Int? = null,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null,
    @SerialName("max_prompt_tokens")
    val maxPromptTokens: Int? = null
)

@Serializable
data class ModelSupports(
    val streaming: Boolean? = null,
    @SerialName("tool_calls")
    val toolCalls: Boolean? = null
)
