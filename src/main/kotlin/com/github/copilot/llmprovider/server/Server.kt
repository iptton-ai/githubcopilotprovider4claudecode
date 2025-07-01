package com.github.copilot.llmprovider.server

import com.github.copilot.llmprovider.api.claudeApi
import com.github.copilot.llmprovider.api.openAIApi
import com.github.copilot.llmprovider.auth.AuthManager
import com.github.copilot.llmprovider.service.ProxyService
import com.github.copilot.llmprovider.service.ProxyServiceImpl
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*

import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 配置 Ktor 服务器
 */
fun Application.configureServer(authManager: AuthManager) {
    val proxyService: ProxyService = ProxyServiceImpl(authManager)
    // 配置 JSON 序列化
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = false
            coerceInputValues = true
            classDiscriminator = "class_type"  // 避免与 type 属性冲突
        })
    }

    // 配置 CORS
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader(HttpHeaders.AccessControlAllowHeaders)
        allowHeader(HttpHeaders.AccessControlAllowMethods)
        allowCredentials = true
        anyHost() // 在生产环境中应该限制特定域名
    }

    // TODO: 添加请求日志记录

    // 配置状态页面和错误处理
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
        
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                mapOf("error" to "Endpoint not found")
            )
        }
        
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(
                status,
                mapOf("error" to "Method not allowed")
            )
        }
    }

    // 配置路由
    routing {
        // 健康检查端点
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }

        // API 版本信息
        get("/") {
            call.respond(mapOf(
                "name" to "GitHub Copilot LLM Provider",
                "version" to "1.0.0",
                "description" to "A proxy service for OpenAI and Claude API compatibility"
            ))
        }

        // API 路由
        route("/v1") {
            openAIApi(proxyService)
            claudeApi(proxyService)
        }
    }
}

/**
 * 启动服务器的配置
 */
object ServerConfig {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host: String = System.getenv("HOST") ?: "0.0.0.0"
    val targetApiUrl: String = System.getenv("TARGET_API_URL") ?: "http://localhost:11434"
}
