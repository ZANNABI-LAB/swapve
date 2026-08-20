package dev.swapve.csms.ws

import dev.swapve.csms.config.CsmsProperties
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * OCPP 보안 프로파일 1 — HTTP Basic 인증.
 *
 * 프레임워크와 I/O 를 모른다. 핸드셰이크 인터셉터가 헤더 문자열과 경로에서 확인한
 * stationId 를 넘기면, 여기서는 파싱과 BCrypt 대조만 한다.
 */
class BasicAuthenticator(
    credentials: List<CsmsProperties.StationCredential>,
    private val passwordEncoder: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) {

    private val credentials = credentials.toList()

    fun authenticate(stationId: String, authorization: String?): Result {
        val parsed = parse(authorization)
        val username = parsed?.username.orEmpty()
        val password = parsed?.password.orEmpty()

        var selectedHash: String? = null
        credentials.forEach { credential ->
            if (constantEquals(credential.stationId, username)) {
                selectedHash = credential.passwordHash
            }
        }

        val usernameMatchesPath = parsed != null && constantEquals(username, stationId)
        val fallbackHash = credentials.firstOrNull()?.passwordHash ?: DECOY_BCRYPT_HASH
        val passwordMatches = passwordEncoder.matches(password, selectedHash ?: fallbackHash)

        return if (parsed != null && selectedHash != null && usernameMatchesPath && passwordMatches) {
            Result(true, "인증 성공")
        } else {
            Result(false, "등록되지 않았거나 자격증명이 맞지 않는다")
        }
    }

    fun parse(authorization: String?): Parsed? {
        if (authorization == null) return null
        val parts = authorization.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Basic", ignoreCase = true)) return null

        val decoded = runCatching {
            Base64.getDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null

        val separator = decoded.indexOf(':')
        if (separator < 0) return null
        return Parsed(
            username = decoded.substring(0, separator),
            password = decoded.substring(separator + 1),
        )
    }

    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    data class Parsed(val username: String, val password: String)

    data class Result(val authenticated: Boolean, val reason: String)

    companion object {
        // 잘 형성된 bcrypt 해시. 등록되지 않은 username 도 BCrypt 를 정확히 한 번 태우기 위한 디코이다.
        private const val DECOY_BCRYPT_HASH = "\$2a\$10\$/3xSUn42RCqRPEsU5QmxG.F50C3bcrcOh1Y9g2qjwSwQtIp3N3r9m"
    }
}
