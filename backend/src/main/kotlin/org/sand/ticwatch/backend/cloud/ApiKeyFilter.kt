package org.sand.ticwatch.backend.cloud

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Optional shared-secret guard for a future non-local deployment: with `ticwatch.cloud.api-key` empty
 * (the default on a home LAN) every request passes; set, every API call except [CloudApi.PING] needs
 * `Authorization: Bearer <key>` - the phone already sends its stored token when it has one.
 */
@Component
class ApiKeyFilter(@Value("\${ticwatch.cloud.api-key:}") private val apiKey: String) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        apiKey.isBlank() || request.requestURI == CloudApi.PING

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val presented = request.getHeader(HttpHeaders.AUTHORIZATION)?.removePrefix(BEARER_PREFIX).orEmpty()
        if (MessageDigest.isEqual(presented.toByteArray(), apiKey.toByteArray())) {
            chain.doFilter(request, response)
        } else {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
        }
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
