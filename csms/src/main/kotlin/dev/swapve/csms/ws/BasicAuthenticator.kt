package dev.swapve.csms.ws

import dev.swapve.csms.auth.BasicCredentials
import dev.swapve.csms.config.CsmsProperties
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * OCPP 보안 프로파일 1 — HTTP Basic 인증.
 *
 * 프레임워크와 I/O 를 모른다. 핸드셰이크 인터셉터가 헤더 문자열과 경로에서 확인한
 * stationId 를 넘기면, 여기서는 파싱과 BCrypt 대조만 한다.
 */
class BasicAuthenticator(
    credentials: List<CsmsProperties.StationCredential>,
    passwordEncoder: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) {

    private val credentials = BasicCredentials(
        credentials.map { BasicCredentials.Credential(it.stationId, it.passwordHash) },
        passwordEncoder,
    )

    fun authenticate(stationId: String, authorization: String?): Result {
        val result = credentials.authenticate(authorization)
        val usernameMatchesPath = result.parsed != null &&
            BasicCredentials.constantEquals(result.parsed.username, stationId)

        return if (result.authenticated && usernameMatchesPath) {
            Result(true, "인증 성공")
        } else {
            Result(false, "등록되지 않았거나 자격증명이 맞지 않는다")
        }
    }

    fun parse(authorization: String?): Parsed? {
        val parsed = credentials.parse(authorization) ?: return null
        return Parsed(parsed.username, parsed.password)
    }

    data class Parsed(val username: String, val password: String)

    data class Result(val authenticated: Boolean, val reason: String)
}
