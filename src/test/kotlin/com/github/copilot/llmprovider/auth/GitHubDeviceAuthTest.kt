package com.github.copilot.llmprovider.auth

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试 GitHub Device Auth 流程
 * 
 * 测试理由：
 * - 验证设备代码请求的正确性
 * - 确保轮询逻辑的正确实现
 * - 测试错误处理和重试机制
 * - 验证 OAuth token 的获取
 */
class GitHubDeviceAuthTest {

    @Test
    fun `should request device code successfully`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login/device/code" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "device_code": "test_device_code",
                                "user_code": "TEST-CODE",
                                "verification_uri": "https://github.com/login/device",
                                "expires_in": 900,
                                "interval": 5
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val deviceAuth = GitHubDeviceAuth(mockEngine)

        // Act
        val deviceCodeResponse = deviceAuth.requestDeviceCode()

        // Assert
        assertNotNull(deviceCodeResponse)
        assertEquals("test_device_code", deviceCodeResponse.deviceCode)
        assertEquals("TEST-CODE", deviceCodeResponse.userCode)
        assertEquals("https://github.com/login/device", deviceCodeResponse.verificationUri)
        assertEquals(900, deviceCodeResponse.expiresIn)
        assertEquals(5, deviceCodeResponse.interval)
    }

    @Test
    fun `should poll for access token successfully`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "access_token": "ghu_test_access_token",
                                "token_type": "bearer",
                                "scope": "copilot"
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val deviceAuth = GitHubDeviceAuth(mockEngine)

        // Act
        val accessToken = deviceAuth.pollForAccessToken("test_device_code", 1)

        // Assert
        assertNotNull(accessToken)
        assertEquals("ghu_test_access_token", accessToken.accessToken)
        assertEquals("bearer", accessToken.tokenType)
        assertEquals("copilot", accessToken.scope)
    }

    @Test
    fun `should handle authorization pending during polling`() = runBlocking {
        // Arrange
        var callCount = 0
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> {
                    callCount++
                    if (callCount == 1) {
                        respond(
                            content = ByteReadChannel("""
                                {
                                    "error": "authorization_pending",
                                    "error_description": "The authorization request is still pending"
                                }
                            """.trimIndent()),
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("""
                                {
                                    "access_token": "ghu_test_access_token",
                                    "token_type": "bearer",
                                    "scope": "copilot"
                                }
                            """.trimIndent()),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val deviceAuth = GitHubDeviceAuth(mockEngine)

        // Act
        val accessToken = deviceAuth.pollForAccessToken("test_device_code", 1, maxAttempts = 2)

        // Assert
        assertNotNull(accessToken)
        assertEquals("ghu_test_access_token", accessToken.accessToken)
        assertTrue(callCount >= 2)
    }

    @Test
    fun `should handle slow down error during polling`() = runBlocking {
        // Arrange
        var callCount = 0
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> {
                    callCount++
                    if (callCount == 1) {
                        respond(
                            content = ByteReadChannel("""
                                {
                                    "error": "slow_down",
                                    "error_description": "You are polling too frequently"
                                }
                            """.trimIndent()),
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("""
                                {
                                    "access_token": "ghu_test_access_token",
                                    "token_type": "bearer",
                                    "scope": "copilot"
                                }
                            """.trimIndent()),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val deviceAuth = GitHubDeviceAuth(mockEngine)

        // Act
        val accessToken = deviceAuth.pollForAccessToken("test_device_code", 1, maxAttempts = 2)

        // Assert
        assertNotNull(accessToken)
        assertEquals("ghu_test_access_token", accessToken.accessToken)
        assertTrue(callCount >= 2)
    }

    @Test
    fun `should get user info with access token`() = runBlocking {
        // Arrange
        val mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/user" -> {
                    respond(
                        content = ByteReadChannel("""
                            {
                                "login": "testuser",
                                "id": 12345,
                                "name": "Test User"
                            }
                        """.trimIndent()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled ${request.url.encodedPath}")
            }
        }

        val deviceAuth = GitHubDeviceAuth(mockEngine)

        // Act
        val userInfo = deviceAuth.getUserInfo("ghu_test_token")

        // Assert
        assertNotNull(userInfo)
        assertEquals("testuser", userInfo.login)
        assertEquals(12345, userInfo.id)
        assertEquals("Test User", userInfo.name)
    }
}
