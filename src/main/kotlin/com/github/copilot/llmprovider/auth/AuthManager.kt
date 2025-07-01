package com.github.copilot.llmprovider.auth

import com.github.copilot.llmprovider.service.CopilotApiToken
import com.github.copilot.llmprovider.service.GitHubCopilotService
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 认证管理器
 * 负责管理整个认证流程，包括 OAuth token 和 API token
 */
class AuthManager {
    private val configManager = GitHubConfigManager()
    private val deviceAuth = GitHubDeviceAuth()
    private val copilotService = GitHubCopilotService()
    
    @Volatile
    private var cachedApiToken: CopilotApiToken? = null
    @Volatile
    private var cachedOAuthToken: String? = null

    /**
     * 获取有效的 API token
     */
    suspend fun getValidApiToken(): String {
        // 检查缓存的 API token 是否仍然有效
        cachedApiToken?.let { token ->
            if (!isTokenExpiredOrExpiringSoon(token)) {
                logger.debug { "Using cached API token" }
                return token.token
            } else {
                if (copilotService.isTokenExpired(token)) {
                    logger.info { "Cached API token expired, refreshing..." }
                } else {
                    logger.info { "Cached API token expiring soon (within 5 minutes), proactively refreshing..." }
                }
                cachedApiToken = null
            }
        }

        return refreshApiToken()
    }

    /**
     * 强制刷新 API token
     */
    suspend fun forceRefreshApiToken(): String {
        logger.info { "Force refreshing API token..." }
        cachedApiToken = null
        return refreshApiToken()
    }

    /**
     * 刷新 API token
     */
    private suspend fun refreshApiToken(): String {
        // 获取 OAuth token
        val oauthToken = getValidOAuthToken()

        // 使用 OAuth token 获取新的 API token
        val apiToken = copilotService.getApiToken(oauthToken)
        cachedApiToken = apiToken

        logger.info { "API token refreshed successfully, expires at: ${apiToken.expiresAt}" }
        return apiToken.token
    }

    /**
     * 获取有效的 OAuth token
     */
    suspend fun getValidOAuthToken(): String {
        // 检查缓存的 OAuth token
        cachedOAuthToken?.let { token ->
            logger.debug { "Using cached OAuth token" }
            return token
        }

        // 尝试从配置文件获取 OAuth token
        val existingToken = configManager.getOAuthToken()
        if (existingToken != null) {
            logger.info { "Found existing OAuth token in config" }
            cachedOAuthToken = existingToken
            return existingToken
        }

        // 如果没有现有 token，启动设备授权流程
        logger.info { "No existing OAuth token found, starting device authorization..." }
        val newToken = deviceAuth.performDeviceAuthFlow()
        
        if (newToken != null) {
            // 获取用户信息并保存 token
            val userInfo = deviceAuth.getUserInfo(newToken)
            configManager.saveOAuthToken(newToken, userInfo.login)
            cachedOAuthToken = newToken
            return newToken
        } else {
            throw Exception("Failed to obtain OAuth token through device authorization")
        }
    }

    /**
     * 初始化认证（检查现有配置并获取模型列表）
     */
    suspend fun initialize(): String {
        logger.info { "Initializing authentication..." }
        
        try {
            // 获取有效的 API token
            val apiToken = getValidApiToken()
            
            // 获取并显示支持的模型
            copilotService.getSupportedModels(apiToken)
            
            // 获取首选的 Claude 模型
            val preferredModel = copilotService.getPreferredClaudeModel(apiToken)
            
            println("✅ Authentication successful!")
            println("🎯 Preferred model: $preferredModel")
            
            return preferredModel
        } catch (e: Exception) {
            logger.error(e) { "Authentication initialization failed" }
            throw e
        }
    }

    /**
     * 清除缓存的 token
     */
    fun clearCache() {
        cachedApiToken = null
        cachedOAuthToken = null
        logger.info { "Authentication cache cleared" }
    }

    /**
     * 获取 Copilot 服务实例
     */
    fun getCopilotService(): GitHubCopilotService = copilotService

    /**
     * 检查 token 是否已过期或即将过期（5分钟内）
     */
    private fun isTokenExpiredOrExpiringSoon(token: CopilotApiToken): Boolean {
        val now = System.currentTimeMillis()
        val expiryTime = token.expiresAt
        val fiveMinutesInMs = 5L * 60L * 1000L // 5分钟 = 300,000毫秒

        // 如果已经过期，返回 true
        if (now >= expiryTime) {
            logger.debug { "Token is expired (now: $now, expires: $expiryTime)" }
            return true
        }

        // 如果距离过期时间少于5分钟，也返回 true
        val timeUntilExpiry = expiryTime - now
        if (timeUntilExpiry <= fiveMinutesInMs) {
            val minutesLeft = timeUntilExpiry / (60L * 1000L)
            logger.debug { "Token expiring soon (${minutesLeft} minutes left)" }
            return true
        }

        // Token 还有超过5分钟才过期
        val minutesLeft = timeUntilExpiry / (60L * 1000L)
        logger.debug { "Token is valid (${minutesLeft} minutes until expiry)" }
        return false
    }

    /**
     * 关闭资源
     */
    fun close() {
        deviceAuth.close()
        copilotService.close()
    }
}
