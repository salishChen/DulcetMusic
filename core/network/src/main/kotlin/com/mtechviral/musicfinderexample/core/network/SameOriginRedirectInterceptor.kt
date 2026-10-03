package com.mtechviral.musicfinderexample.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Allows path redirects without sending source credentials outside the original origin. */
object SameOriginRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val initial = chain.request()
        var request = initial
        var redirects = 0
        while (true) {
            val response = chain.proceed(request)
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val target = response.header("Location")?.let { request.url.resolve(it) }
            response.close()
            if (target == null) throw IOException("远程音频重定向地址无效")
            val origin = initial.url
            if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port ||
                target.username.isNotEmpty() || target.password.isNotEmpty()
            ) throw IOException("远程音频重定向到不同服务器，已拒绝发送认证信息")
            if (++redirects > 5) throw IOException("远程音频重定向次数过多")
            // The original Call covers every hop, so cancellation also stops redirects.
            // Preserve authentication and Range for same-origin GET redirects, including seeks.
            request = request.newBuilder().url(target).build()
        }
    }
}
