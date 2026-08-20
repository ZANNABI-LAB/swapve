package dev.swapve.csms.auth

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * HTTP Basic 자격증명 검증 코어.
 *
 * I/O 경계를 모른다. 호출자는 헤더 문자열과 `username -> bcrypt hash` 목록만 넘긴다.
 * 등록되지 않은 사용자도 BCrypt 를 정확히 한 번 지나게 하려고 디코이 해시를 쓴다.
 */
class BasicCredentials(
    credentials: List<Credential>,
    private val passwordEncoder: BCryptPasswordEncoder = BCryptPasswordEncoder(),
) {

    private val credentials = credentials.toList()

    fun authenticate(authorization: String?): Result {
        val parsed = parse(authorization)
        val username = parsed?.username.orEmpty()
        val password = parsed?.password.orEmpty()

        var selectedHash: String? = null
        credentials.forEach { credential ->
            if (constantEquals(credential.username, username)) {
                selectedHash = credential.passwordHash
            }
        }

        val fallbackHash = credentials.firstOrNull()?.passwordHash ?: DECOY_BCRYPT_HASH
        val passwordMatches = passwordEncoder.matches(password, selectedHash ?: fallbackHash)
        val authenticated = parsed != null && selectedHash != null && passwordMatches

        return Result(authenticated, parsed)
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

    data class Credential(val username: String, val passwordHash: String)

    data class Parsed(val username: String, val password: String)

    data class Result(val authenticated: Boolean, val parsed: Parsed?)

    companion object {
        fun constantEquals(left: String, right: String): Boolean =
            MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

        // 잘 형성된 bcrypt 해시. 등록되지 않은 username 도 BCrypt 를 정확히 한 번 태운다.
        private const val DECOY_BCRYPT_HASH = "\$2a\$10\$/3xSUn42RCqRPEsU5QmxG.F50C3bcrcOh1Y9g2qjwSwQtIp3N3r9m"
    }
}
