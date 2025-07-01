package com.github.copilot.llmprovider.api

import com.github.copilot.llmprovider.cli.CliMonitor
import com.github.copilot.llmprovider.model.*
import com.github.copilot.llmprovider.service.ProxyService
import com.github.copilot.llmprovider.util.FlexibleClaudeParser
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
 * Claude API 兼容接口
 */
fun Route.claudeApi(proxyService: ProxyService) {
    val cliMonitor = CliMonitor.getInstance()
    
    post("/messages") {
        val startTime = System.currentTimeMillis()
        
        try {
            // 解析请求
            val requestBody = call.receiveText()

            // Debug 模式：完整打印请求
            // println("\n🔍 DEBUG: Raw Claude Request")
            // println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            // println("📄 Content-Type: ${call.request.contentType()}")
            // println("📏 Content-Length: ${requestBody.length}")
            // println("📋 Headers:")
            // call.request.headers.forEach { name, values ->
            //     println("   $name: ${values.joinToString(", ")}")
            // }
            // println("📋 Raw JSON:")
            // println(requestBody)
            // println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val request = try {
                FlexibleClaudeParser.parseClaudeRequest(requestBody)
            } catch (e: Exception) {
                println("❌ JSON Parsing Error:")
                println("   Error Type: ${e::class.simpleName}")
                println("   Error Message: ${e.message}")
                println("   Stack Trace:")
                e.printStackTrace()
                logger.warn(e) { "Failed to parse Claude request" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    ClaudeErrorResponse(
                        type = "error",
                        error = ClaudeError(
                            type = "invalid_request_error",
                            message = "Invalid JSON in request body"
                        )
                    )
                )
                return@post
            }

            // 验证请求
            val validationError = validateClaudeRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, validationError)
                return@post
            }

            // 记录请求到 CLI
            cliMonitor.logRequest(
                method = "POST",
                path = "/v1/messages",
                status = 200,
                duration = 0,
                requestBody = requestBody.take(200)
            )

            if (request.stream) {
                // 流式响应
                handleClaudeStreamingResponse(call, request, proxyService, startTime)
            } else {
                // 普通 JSON 响应
                handleClaudeJsonResponse(call, request, proxyService, startTime)
            }

        } catch (e: Exception) {
            logger.error(e) { "Error processing Claude message request" }
            val duration = System.currentTimeMillis() - startTime
            
            cliMonitor.logRequest(
                method = "POST",
                path = "/v1/messages",
                status = 500,
                duration = duration,
                responseBody = "Internal server error"
            )
            
            call.respond(
                HttpStatusCode.InternalServerError,
                ClaudeErrorResponse(
                    type = "error",
                    error = ClaudeError(
                        type = "internal_error",
                        message = "Internal server error"
                    )
                )
            )
        }
    }
}

/**
 * 验证 Claude 请求
 */
private fun validateClaudeRequest(request: FlexibleClaudeParser.FlexibleClaudeRequest): ClaudeErrorResponse? {
    val errors = FlexibleClaudeParser.validateRequest(request)

    if (errors.isNotEmpty()) {
        return ClaudeErrorResponse(
            type = "error",
            error = ClaudeError(
                type = "invalid_request_error",
                message = errors.first()
            )
        )
    }

    return null
}

/**
 * 处理 Claude 流式响应
 */
private suspend fun handleClaudeStreamingResponse(
    call: ApplicationCall,
    request: FlexibleClaudeParser.FlexibleClaudeRequest,
    proxyService: ProxyService,
    startTime: Long
) {
    call.response.header(HttpHeaders.ContentType, "text/event-stream")
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    
    try {
        // 使用真实的代理服务进行流式转发
        val responseFlow = proxyService.forwardClaudeStreamRequest(request)
        
        responseFlow.collect { chunk ->
            call.respondText("data: $chunk\n\n")
        }
        
        // 发送结束标记
        call.respondText("data: [DONE]\n\n")
        
        val duration = System.currentTimeMillis() - startTime
        CliMonitor.getInstance().logRequest(
            method = "POST",
            path = "/v1/messages",
            status = 200,
            duration = duration,
            responseBody = "Streaming response completed"
        )
        
    } catch (e: Exception) {
        logger.error(e) { "Error in Claude streaming response" }
        call.respondText("data: {\"error\": \"Stream error\"}\n\n")
    }
}

/**
 * 处理 Claude JSON 响应
 */
private suspend fun handleClaudeJsonResponse(
    call: ApplicationCall,
    request: FlexibleClaudeParser.FlexibleClaudeRequest,
    proxyService: ProxyService,
    startTime: Long
) {
    try {
        // 使用真实的代理服务转发请求
        val response = proxyService.forwardClaudeRequest(request)
        
        call.respond(HttpStatusCode.OK, response)
        
        val duration = System.currentTimeMillis() - startTime
        val responseBody = Json.encodeToString(ClaudeMessageResponse.serializer(), response)
        
        CliMonitor.getInstance().logRequest(
            method = "POST",
            path = "/v1/messages",
            status = 200,
            duration = duration,
            responseBody = responseBody.take(200)
        )
        
    } catch (e: Exception) {
        logger.error(e) { "Error in Claude JSON response" }
        call.respond(
            HttpStatusCode.InternalServerError,
            ClaudeErrorResponse(
                type = "error",
                error = ClaudeError(
                    type = "internal_error",
                    message = "Failed to process request: ${e.message}"
                )
            )
        )
    }
}


