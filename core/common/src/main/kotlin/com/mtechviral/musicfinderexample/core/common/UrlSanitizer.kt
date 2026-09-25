package com.mtechviral.musicfinderexample.core.common

/**
 * URL / 日志脱敏（优化建议 01）。
 *
 * Subsonic 流地址与 API 地址携带认证查询参数（`u` 用户名、`s` 盐、`t` 认证 token），
 * 任何把这些 URL 写进日志、异常消息或界面错误提示的地方都会扩大凭据暴露面。
 * 统一用 [redact] 把这三个参数替换为 `***` 再输出。
 */
object UrlSanitizer {

    /** 认证参数（u / s / t）的查询串片段 */
    private val AUTH_PARAM = Regex("([?&])(u|s|t)=[^&\\s'\"]*")

    /** 把文本中出现的认证参数值替换为 `***`（幂等，可重复调用） */
    fun redact(text: String): String = text.replace(AUTH_PARAM, "$1$2=***")
}
