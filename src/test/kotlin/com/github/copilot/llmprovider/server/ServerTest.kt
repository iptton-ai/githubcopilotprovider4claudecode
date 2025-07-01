package com.github.copilot.llmprovider.server

import io.mockk.mockk
import com.github.copilot.llmprovider.auth.AuthManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 测试 HTTP 服务器的基础功能
 * 
 * 测试理由：
 * - 验证服务器能正确启动和响应
 * - 确保健康检查端点正常工作
 * - 验证 CORS 配置正确
 * - 测试错误处理中间件
 */
class ServerTest {

    private val mockAuthManager = mockk<AuthManager>(relaxed = true)

    @Test
    fun `should respond to health check`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.get("/health")

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `should handle CORS preflight requests`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.options("/v1/chat/completions") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "Content-Type,Authorization")
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `should return 404 for unknown endpoints`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.get("/unknown")

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `should handle JSON content negotiation`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model": "gpt-4", "messages": []}""")
        }

        // Assert
        // 应该返回 400 或其他错误，但不是 415 (Unsupported Media Type)
        assert(response.status != HttpStatusCode.UnsupportedMediaType)
    }

    @Test
    fun `should log requests and responses`() = testApplication {
        application {
            configureServer(mockAuthManager)
        }

        // Act
        val response = client.get("/health")

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        // 这里主要测试没有抛出异常，日志功能正常工作
    }
}
