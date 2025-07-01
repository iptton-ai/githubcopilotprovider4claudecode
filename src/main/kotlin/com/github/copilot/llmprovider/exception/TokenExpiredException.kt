package com.github.copilot.llmprovider.exception

/**
 * Token 过期异常
 * 当 GitHub Copilot API 返回 401 Unauthorized 时抛出
 */
class TokenExpiredException(
    message: String,
    val statusCode: Int = 401,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 速率限制异常 (429 Too Many Requests)
 * 当 API 返回 429 错误时抛出，通常需要降级模型或重试
 */
class RateLimitException(
    message: String,
    val statusCode: Int = 429,
    val retryAfter: Long? = null,
    cause: Throwable? = null
) : Exception(message, cause)
