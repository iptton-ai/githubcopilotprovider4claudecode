package com.github.copilot.llmprovider.cli

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = KotlinLogging.logger {}

/**
 * CLI 监控界面，实时显示请求和响应
 */
class CliMonitor {
    private val terminal = Terminal()
    private val requestLogs = ConcurrentLinkedQueue<RequestLog>()
    private val maxLogs = 50 // 最多保留50条日志
    
    data class RequestLog(
        val timestamp: LocalDateTime,
        val method: String,
        val path: String,
        val status: Int,
        val duration: Long,
        val requestBody: String? = null,
        val responseBody: String? = null
    )

    /**
     * 启动 CLI 监控界面
     */
    suspend fun start() {
        terminal.cursor.hide()
        
        try {
            while (true) {
                clearScreen()
                displayHeader()
                displayStats()
                displayLogs()
                delay(1000) // 每秒刷新一次
            }
        } finally {
            terminal.cursor.show()
        }
    }

    /**
     * 记录请求日志
     */
    fun logRequest(
        method: String,
        path: String,
        status: Int,
        duration: Long,
        requestBody: String? = null,
        responseBody: String? = null
    ) {
        val log = RequestLog(
            timestamp = LocalDateTime.now(),
            method = method,
            path = path,
            status = status,
            duration = duration,
            requestBody = requestBody,
            responseBody = responseBody
        )
        
        requestLogs.offer(log)
        
        // 保持日志数量在限制内
        while (requestLogs.size > maxLogs) {
            requestLogs.poll()
        }
        
        logger.debug { "Logged request: $method $path -> $status (${duration}ms)" }
    }

    private fun clearScreen() {
        terminal.print("\u001b[2J\u001b[H")
    }

    private fun displayHeader() {
        val title = bold(blue("GitHub Copilot LLM Provider"))
        val subtitle = dim("Real-time Request Monitor")
        val timestamp = dim("Last updated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        
        terminal.println()
        terminal.println("  $title")
        terminal.println("  $subtitle")
        terminal.println("  $timestamp")
        terminal.println()
        terminal.println("  " + "=".repeat(80))
        terminal.println()
    }

    private fun displayStats() {
        val totalRequests = requestLogs.size
        val successRequests = requestLogs.count { it.status in 200..299 }
        val errorRequests = requestLogs.count { it.status >= 400 }
        val avgDuration = if (requestLogs.isNotEmpty()) {
            requestLogs.map { it.duration }.average().toLong()
        } else 0L

        terminal.println("  ${bold("Statistics:")}")
        terminal.println("    Total Requests: ${cyan(totalRequests.toString())}")
        terminal.println("    Success: ${green(successRequests.toString())}")
        terminal.println("    Errors: ${red(errorRequests.toString())}")
        terminal.println("    Avg Duration: ${yellow("${avgDuration}ms")}")
        terminal.println()
    }

    private fun displayLogs() {
        terminal.println("  ${bold("Recent Requests:")}")
        terminal.println()
        
        if (requestLogs.isEmpty()) {
            terminal.println("    ${dim("No requests yet...")}")
            return
        }

        // 显示最近的请求（最新的在上面）
        requestLogs.toList().takeLast(20).reversed().forEach { log: RequestLog ->
            val timestamp = log.timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val method = when (log.method) {
                "GET" -> blue(log.method)
                "POST" -> green(log.method)
                "PUT" -> yellow(log.method)
                "DELETE" -> red(log.method)
                else -> cyan(log.method)
            }
            
            val status = when {
                log.status in 200..299 -> green(log.status.toString())
                log.status in 300..399 -> yellow(log.status.toString())
                log.status >= 400 -> red(log.status.toString())
                else -> dim(log.status.toString())
            }
            
            val duration = if (log.duration > 1000) {
                red("${log.duration}ms")
            } else if (log.duration > 500) {
                yellow("${log.duration}ms")
            } else {
                green("${log.duration}ms")
            }

            terminal.println("    ${dim(timestamp)} $method ${log.path} -> $status ($duration)")
            
            // 显示请求体（如果有且不为空）
            if (!log.requestBody.isNullOrBlank() && log.requestBody.length < 200) {
                terminal.println("      ${dim("Request:")} ${log.requestBody.take(100)}${if (log.requestBody.length > 100) "..." else ""}")
            }
            
            // 显示响应体（如果有且不为空）
            if (!log.responseBody.isNullOrBlank() && log.responseBody.length < 200) {
                terminal.println("      ${dim("Response:")} ${log.responseBody.take(100)}${if (log.responseBody.length > 100) "..." else ""}")
            }
        }
        
        terminal.println()
        terminal.println("  ${dim("Press Ctrl+C to stop the server")}")
    }

    companion object {
        @Volatile
        private var instance: CliMonitor? = null
        
        fun getInstance(): CliMonitor {
            return instance ?: synchronized(this) {
                instance ?: CliMonitor().also { instance = it }
            }
        }
    }
}
