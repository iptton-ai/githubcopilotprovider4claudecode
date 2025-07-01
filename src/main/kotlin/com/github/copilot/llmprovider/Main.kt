package com.github.copilot.llmprovider

import com.github.copilot.llmprovider.auth.AuthManager
import com.github.copilot.llmprovider.cli.CliMonitor
import com.github.copilot.llmprovider.server.ServerConfig
import com.github.copilot.llmprovider.server.configureServer
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 应用程序主入口
 */
fun main(args: Array<String>) {
    runBlocking {
        logger.info { "Starting GitHub Copilot LLM Provider..." }
        logger.info { "Server will listen on ${ServerConfig.host}:${ServerConfig.port}" }

        // 初始化认证管理器
        val authManager = AuthManager()

        try {
            // 初始化认证（这会检查现有配置或启动设备授权流程）
            logger.info { "Initializing GitHub Copilot authentication..." }
            authManager.initialize()

            // 启动 CLI 监控界面
            val cliMonitor = CliMonitor()
            val cliJob = launch {
                cliMonitor.start()
            }

            // 启动 HTTP 服务器
            val server = embeddedServer(
                Netty,
                port = ServerConfig.port,
                host = ServerConfig.host
            ) {
                configureServer(authManager)
            }

            try {
                server.start(wait = true)
            } catch (e: Exception) {
                logger.error(e) { "Failed to start server" }
                cliJob.cancel()
                throw e
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize authentication" }
            authManager.close()
            throw e
        }
    }
}
