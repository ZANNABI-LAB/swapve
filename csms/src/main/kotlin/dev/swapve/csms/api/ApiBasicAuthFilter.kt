package dev.swapve.csms.api

import dev.swapve.csms.auth.BasicCredentials
import dev.swapve.csms.config.CsmsProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicBoolean

class ApiBasicAuthFilter(
    private val security: CsmsProperties.ApiSecurity,
) : OncePerRequestFilter() {

    private val warnedEmptyUsers = AtomicBoolean(false)
    private val credentials = BasicCredentials(
        security.users.map { BasicCredentials.Credential(it.username, it.passwordHash) },
    )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!security.enabled) {
            filterChain.doFilter(request, response)
            return
        }

        if (security.users.isEmpty()) {
            if (warnedEmptyUsers.compareAndSet(false, true)) {
                log.warn("csms.api.security.users 가 비어 있어 /api/* 요청을 모두 401 로 거절한다")
            }
            reject(response)
            return
        }

        val result = credentials.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (!result.authenticated) {
            reject(response)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"${security.realm}\", charset=\"UTF-8\"")
        response.contentType = "application/json; charset=utf-8"
        response.writer.write("""{"error":"UNAUTHORIZED"}""")
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiBasicAuthFilter::class.java)
    }
}
