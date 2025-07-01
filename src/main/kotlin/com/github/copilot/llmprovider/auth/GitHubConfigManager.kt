package com.github.copilot.llmprovider.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * GitHub Copilot 配置管理器
 * 负责读取和保存 GitHub Copilot 的 OAuth 配置
 */
class GitHubConfigManager(
    private val homeDir: String = System.getProperty("user.home")
) {
    companion object {
        const val TARGET_CLIENT_ID = "Iv23ctfURkiMfJ4xr5mv"
        const val CONFIG_PATH = ".config/github-copilot/apps.json"
        const val APP_CONFIG_PATH = ".config/app.json"
    }

    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val githubConfigFile = File(homeDir, CONFIG_PATH)
    private val appConfigFile = File(homeDir, APP_CONFIG_PATH)

    /**
     * 检查是否存在现有的 GitHub Copilot 配置
     */
    fun hasExistingConfig(): Boolean {
        return githubConfigFile.exists() && githubConfigFile.isFile
    }

    /**
     * 从现有配置中获取 OAuth token
     */
    fun getExistingOAuthToken(): String? {
        return try {
            if (!hasExistingConfig()) {
                logger.debug { "GitHub Copilot config file not found: ${githubConfigFile.absolutePath}" }
                return null
            }

            val configContent = githubConfigFile.readText()
            val configJson = json.parseToJsonElement(configContent).jsonObject

            // 查找目标 client ID 的配置
            val targetKey = "github.com:$TARGET_CLIENT_ID"
            val targetConfig = configJson[targetKey]?.jsonObject

            if (targetConfig != null) {
                val oauthToken = targetConfig["oauth_token"]?.jsonPrimitive?.content
                logger.info { "Found existing OAuth token for client ID: $TARGET_CLIENT_ID" }
                return oauthToken
            }

            // 如果没有找到目标 client ID，尝试获取任何可用的 token
            for ((key, value) in configJson) {
                if (key.startsWith("github.com:")) {
                    val config = value.jsonObject
                    val oauthToken = config["oauth_token"]?.jsonPrimitive?.content
                    if (oauthToken != null) {
                        logger.info { "Found OAuth token for client ID: ${key.substringAfter("github.com:")}" }
                        return oauthToken
                    }
                }
            }

            logger.warn { "No OAuth token found in GitHub Copilot config" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse GitHub Copilot config" }
            null
        }
    }

    /**
     * 保存 OAuth token 到配置文件
     */
    fun saveOAuthToken(oauthToken: String, user: String) {
        try {
            // 确保目录存在
            appConfigFile.parentFile.mkdirs()

            // 读取现有配置或创建新配置
            val existingConfig = if (appConfigFile.exists()) {
                try {
                    json.parseToJsonElement(appConfigFile.readText()).jsonObject.toMutableMap()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse existing app config, creating new one" }
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }

            // 添加新的配置项
            val targetKey = "github.com:$TARGET_CLIENT_ID"
            val configEntry = GitHubConfigEntry(
                oauthToken = oauthToken,
                user = user,
                githubAppId = TARGET_CLIENT_ID
            )

            existingConfig[targetKey] = json.parseToJsonElement(
                json.encodeToString(GitHubConfigEntry.serializer(), configEntry)
            )

            // 保存配置
            val configJson = JsonObject(existingConfig)
            appConfigFile.writeText(json.encodeToString(JsonObject.serializer(), configJson))

            logger.info { "OAuth token saved to: ${appConfigFile.absolutePath}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save OAuth token" }
            throw e
        }
    }

    /**
     * 从应用配置中获取 OAuth token
     */
    fun getOAuthTokenFromAppConfig(): String? {
        return try {
            if (!appConfigFile.exists()) {
                return null
            }

            val configContent = appConfigFile.readText()
            val configJson = json.parseToJsonElement(configContent).jsonObject

            val targetKey = "github.com:$TARGET_CLIENT_ID"
            val targetConfig = configJson[targetKey]?.jsonObject

            targetConfig?.get("oauth_token")?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.error(e) { "Failed to read OAuth token from app config" }
            null
        }
    }

    /**
     * 获取 OAuth token（优先从应用配置，然后从 GitHub Copilot 配置）
     */
    fun getOAuthToken(): String? {
        return getOAuthTokenFromAppConfig() ?: getExistingOAuthToken()
    }
}

/**
 * GitHub 配置项
 */
@Serializable
data class GitHubConfigEntry(
    @SerialName("oauth_token")
    val oauthToken: String,
    val user: String,
    @SerialName("githubAppId")
    val githubAppId: String
)
