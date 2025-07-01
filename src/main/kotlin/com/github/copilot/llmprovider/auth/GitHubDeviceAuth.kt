package com.github.copilot.llmprovider.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.awt.Desktop
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * GitHub Device Auth 流程实现
 */
class GitHubDeviceAuth(
    private val httpClientEngine: HttpClientEngine? = null
) {
    companion object {
        const val CLIENT_ID = "Iv23ctfURkiMfJ4xr5mv"
        const val SCOPE = "copilot"
        const val GITHUB_API_BASE = "https://github.com"
        const val GITHUB_API_V3_BASE = "https://api.github.com"
    }

    private val httpClient = if (httpClientEngine != null) {
        HttpClient(httpClientEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    } else {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }

    /**
     * 请求设备代码
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse {
        logger.info { "Requesting device code from GitHub..." }
        
        val response = httpClient.post("$GITHUB_API_BASE/login/device/code") {
            headers {
                append(HttpHeaders.Accept, "application/json")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
            }
            setBody(FormDataContent(Parameters.build {
                append("client_id", CLIENT_ID)
                append("scope", SCOPE)
            }))
        }

        val deviceCodeResponse = response.body<DeviceCodeResponse>()
        logger.info { "Device code received. User code: ${deviceCodeResponse.userCode}" }
        
        return deviceCodeResponse
    }

    /**
     * 打开浏览器进行授权
     */
    fun openBrowserForAuth(verificationUri: String, userCode: String) {
        val authUrl = "$verificationUri?user_code=$userCode"
        
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(authUrl))
                logger.info { "Browser opened for authorization: $authUrl" }
            } else {
                logger.warn { "Desktop not supported. Please manually open: $authUrl" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to open browser. Please manually open: $authUrl" }
        }
    }

    /**
     * 轮询获取访问令牌
     */
    suspend fun pollForAccessToken(
        deviceCode: String, 
        interval: Int,
        maxAttempts: Int = 60
    ): AccessTokenResponse? {
        logger.info { "Polling for access token..." }
        
        var attempts = 0
        var currentInterval = interval
        
        while (attempts < maxAttempts) {
            attempts++
            
            try {
                val response = httpClient.post("$GITHUB_API_BASE/login/oauth/access_token") {
                    headers {
                        append(HttpHeaders.Accept, "application/json")
                        append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
                    }
                    setBody(FormDataContent(Parameters.build {
                        append("client_id", CLIENT_ID)
                        append("device_code", deviceCode)
                        append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    }))
                }

                if (response.status == HttpStatusCode.OK) {
                    val accessTokenResponse = response.body<AccessTokenResponse>()
                    logger.info { "Access token received successfully" }
                    return accessTokenResponse
                } else {
                    val errorResponse = response.body<DeviceAuthErrorResponse>()
                    when (errorResponse.error) {
                        "authorization_pending" -> {
                            logger.debug { "Authorization pending, continuing to poll..." }
                        }
                        "slow_down" -> {
                            logger.debug { "Rate limited, increasing interval" }
                            currentInterval += 5
                        }
                        "expired_token" -> {
                            logger.error { "Device code expired" }
                            return null
                        }
                        "access_denied" -> {
                            logger.error { "User denied authorization" }
                            return null
                        }
                        else -> {
                            logger.error { "Unknown error: ${errorResponse.error} - ${errorResponse.errorDescription}" }
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during polling attempt $attempts" }
            }

            delay(currentInterval * 1000L)
        }

        logger.error { "Max polling attempts reached" }
        return null
    }

    /**
     * 获取用户信息
     */
    suspend fun getUserInfo(accessToken: String): GitHubUserInfo {
        logger.info { "Fetching user info..." }
        
        val response = httpClient.get("$GITHUB_API_V3_BASE/user") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
                append(HttpHeaders.Accept, "application/vnd.github.v3+json")
                append(HttpHeaders.UserAgent, "GitHub-Copilot-LLM-Provider/1.0")
            }
        }

        val userInfo = response.body<GitHubUserInfo>()
        logger.info { "User info received for: ${userInfo.login}" }
        
        return userInfo
    }

    /**
     * 完整的设备授权流程
     */
    suspend fun performDeviceAuthFlow(): String? {
        try {
            // 1. 请求设备代码
            val deviceCodeResponse = requestDeviceCode()
            
            // 2. 打开浏览器
            openBrowserForAuth(deviceCodeResponse.verificationUri, deviceCodeResponse.userCode)
            
            println("\n🔐 GitHub Device Authorization")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("📋 User Code: ${deviceCodeResponse.userCode}")
            println("🌐 Verification URL: ${deviceCodeResponse.verificationUri}")
            println("⏰ Expires in: ${deviceCodeResponse.expiresIn} seconds")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("👆 Please authorize this application in your browser")
            println("⏳ Waiting for authorization...")
            
            // 3. 轮询获取访问令牌
            val accessTokenResponse = pollForAccessToken(
                deviceCodeResponse.deviceCode,
                deviceCodeResponse.interval
            )
            
            if (accessTokenResponse != null) {
                // 4. 获取用户信息
                val userInfo = getUserInfo(accessTokenResponse.accessToken)
                
                println("✅ Authorization successful!")
                println("👤 User: ${userInfo.login}")
                
                return accessTokenResponse.accessToken
            } else {
                println("❌ Authorization failed or timed out")
                return null
            }
        } catch (e: Exception) {
            logger.error(e) { "Device auth flow failed" }
            println("❌ Authorization failed: ${e.message}")
            return null
        }
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_uri")
    val verificationUri: String,
    @SerialName("expires_in")
    val expiresIn: Int,
    val interval: Int
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    val scope: String
)

@Serializable
data class DeviceAuthErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String? = null
)

@Serializable
data class GitHubUserInfo(
    val login: String,
    val id: Long,
    val name: String? = null,
    val email: String? = null
)
