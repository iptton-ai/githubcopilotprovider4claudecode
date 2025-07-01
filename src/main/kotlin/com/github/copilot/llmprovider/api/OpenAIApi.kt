package com.github.copilot.llmprovider.api

import com.github.copilot.llmprovider.cli.CliMonitor
import com.github.copilot.llmprovider.model.*
import com.github.copilot.llmprovider.service.ProxyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * OpenAI API 兼容接口
 */
fun Route.openAIApi(proxyService: ProxyService) {
    val cliMonitor = CliMonitor.getInstance()
    
    post("/chat/completions") {
        val startTime = System.currentTimeMillis()
        
        try {
            // 解析请求
            val requestBody = call.receiveText()
            val request = try {
                Json.decodeFromString<OpenAIChatCompletionRequest>(requestBody)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse OpenAI request" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    OpenAIErrorResponse(
                        error = OpenAIError(
                            message = "Invalid JSON in request body",
                            type = "invalid_request_error"
                        )
                    )
                )
                return@post
            }

            // 验证请求
            val validationError = validateOpenAIRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, validationError)
                return@post
            }

            // 记录请求到 CLI
            cliMonitor.logRequest(
                method = "POST",
                path = "/v1/chat/completions",
                status = 200,
                duration = 0,
                requestBody = requestBody.take(200)
            )

            if (request.stream) {
                // 流式响应
                handleStreamingResponse(call, request, proxyService, startTime)
            } else {
                // 普通 JSON 响应
                handleJsonResponse(call, request, proxyService, startTime)
            }

        } catch (e: Exception) {
            logger.error(e) { "Error processing OpenAI chat completion request" }
            val duration = System.currentTimeMillis() - startTime
            
            cliMonitor.logRequest(
                method = "POST",
                path = "/v1/chat/completions",
                status = 500,
                duration = duration,
                responseBody = "Internal server error"
            )
            
            call.respond(
                HttpStatusCode.InternalServerError,
                OpenAIErrorResponse(
                    error = OpenAIError(
                        message = "Internal server error",
                        type = "internal_error"
                    )
                )
            )
        }
    }
}

/**
 * 验证 OpenAI 请求
 */
private fun validateOpenAIRequest(request: OpenAIChatCompletionRequest): OpenAIErrorResponse? {
    if (request.model.isBlank()) {
        return OpenAIErrorResponse(
            error = OpenAIError(
                message = "Model is required",
                type = "invalid_request_error",
                param = "model"
            )
        )
    }
    
    if (request.messages.isEmpty()) {
        return OpenAIErrorResponse(
            error = OpenAIError(
                message = "At least one message is required",
                type = "invalid_request_error",
                param = "messages"
            )
        )
    }
    
    return null
}

/**
 * 处理流式响应
 */
private suspend fun handleStreamingResponse(
    call: ApplicationCall,
    request: OpenAIChatCompletionRequest,
    proxyService: ProxyService,
    startTime: Long
) {
    call.response.header(HttpHeaders.ContentType, "text/event-stream")
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    
    try {
        // 创建流式响应
        val responseFlow = createStreamingResponse(request, proxyService)
        
        responseFlow.collect { chunk ->
            call.respondText("data: $chunk\n\n")
        }
        
        // 发送结束标记
        call.respondText("data: [DONE]\n\n")
        
        val duration = System.currentTimeMillis() - startTime
        CliMonitor.getInstance().logRequest(
            method = "POST",
            path = "/v1/chat/completions",
            status = 200,
            duration = duration,
            responseBody = "Streaming response completed"
        )
        
    } catch (e: Exception) {
        logger.error(e) { "Error in streaming response" }
        call.respondText("data: {\"error\": \"Stream error\"}\n\n")
    }
}

/**
 * 处理 JSON 响应
 */
private suspend fun handleJsonResponse(
    call: ApplicationCall,
    request: OpenAIChatCompletionRequest,
    proxyService: ProxyService,
    startTime: Long
) {
    try {
        // 创建模拟响应（后续会替换为实际的代理调用）
        val response = createMockResponse(request)
        
        call.respond(HttpStatusCode.OK, response)
        
        val duration = System.currentTimeMillis() - startTime
        val responseBody = Json.encodeToString(OpenAIChatCompletionResponse.serializer(), response)
        
        CliMonitor.getInstance().logRequest(
            method = "POST",
            path = "/v1/chat/completions",
            status = 200,
            duration = duration,
            responseBody = responseBody.take(200)
        )
        
    } catch (e: Exception) {
        logger.error(e) { "Error creating JSON response" }
        throw e
    }
}

/**
 * 创建流式响应流
 */
private fun createStreamingResponse(
    request: OpenAIChatCompletionRequest,
    proxyService: ProxyService
): Flow<String> = flow {
    // 模拟流式响应
    val chunks = listOf("Hello", " there", "! How", " can", " I", " help", " you", " today", "?")
    
    chunks.forEachIndexed { index, chunk ->
        val response = OpenAIChatCompletionResponse(
            id = "chatcmpl-${UUID.randomUUID()}",
            objectType = "chat.completion.chunk",
            created = Instant.now().epochSecond,
            model = request.model,
            choices = listOf(
                OpenAIChoice(
                    index = 0,
                    delta = OpenAIDelta(
                        role = if (index == 0) "assistant" else null,
                        content = chunk
                    ),
                    finishReason = if (index == chunks.size - 1) "stop" else null
                )
            )
        )
        
        emit(Json.encodeToString(OpenAIChatCompletionResponse.serializer(), response))
        kotlinx.coroutines.delay(100) // 模拟网络延迟
    }
}

/**
 * 创建模拟响应
 */
private fun createMockResponse(request: OpenAIChatCompletionRequest): OpenAIChatCompletionResponse {
    return OpenAIChatCompletionResponse(
        id = "chatcmpl-${UUID.randomUUID()}",
        objectType = "chat.completion",
        created = Instant.now().epochSecond,
        model = request.model,
        choices = listOf(
            OpenAIChoice(
                index = 0,
                message = OpenAIMessage(
                    role = "assistant",
                    content = "Hello! I'm a mock response. This will be replaced with actual proxy functionality."
                ),
                finishReason = "stop"
            )
        ),
        usage = OpenAIUsage(
            promptTokens = 10,
            completionTokens = 15,
            totalTokens = 25
        )
    )
}
