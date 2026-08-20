package dev.swapve.csms.ws

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 연결 URL 에서 스테이션 식별자를 꺼내고 검증한다 (Part 4 Edition 2 §3.1.1).
 *
 * > *"엔드포인트 URL 뒤에 `/` + 스테이션 식별자(퍼센트 인코딩)를 붙인다."*
 * > `ws://csms.example.com/ocpp` + `CS001` → `ws://csms.example.com/ocpp/CS001`
 * > `RDAM|123` → `.../ocppj/RDAM%7C123`
 *
 * 규칙 세 가지:
 * - identifierString 이고 **최대 [MAX_LENGTH] 자**다
 * - **콜론(`:`)을 쓸 수 없다.** 이 식별자는 basic 인증의 사용자명으로도 쓰이고 콜론이 그
 *   구분자이기 때문이다
 * - 퍼센트 인코딩되어 온다 — 즉 **디코딩이 먼저고 검증이 나중이다.** 순서가 바뀌면 `%3A` 가
 *   콜론 검사를 그대로 통과한다
 *
 * I/O 도 프레임워크도 없는 순수 함수만 둔다. 핸드셰이크 거절 판정이 서버를 띄우지 않고도
 * 전부 시험되게 하려는 것이다.
 */
object StationIdentity {

    /** Part 4 §3.1.1 — identifierString, 최대 48자. */
    const val MAX_LENGTH = 48

    /** 식별자 추출 결과. 거절 사유를 문자열로 들고 있는 이유는 로그에 남기기 위해서다. */
    sealed interface Outcome {

        /** 규칙을 모두 통과했다. */
        data class Identified(val principal: StationPrincipal) : Outcome

        /** 핸드셰이크를 거절해야 한다. [reason] 은 사람이 읽을 사유다 — 응답 본문에 싣지 않는다. */
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * 요청의 **원문(raw) 경로**에서 식별자를 꺼낸다.
     *
     * 반드시 디코딩되지 않은 경로를 넘겨야 한다 (`URI.getRawPath`). 컨테이너가 이미 디코딩한
     * 값을 받으면 `%2F` 와 진짜 `/` 를 구별할 수 없고, 콜론 검사도 우회된다.
     *
     * @param rawPath 예: `/ocpp/RDAM%7C123`
     * @param endpointPath 설정된 엔드포인트 경로. 예: `/ocpp`
     */
    fun fromRawPath(rawPath: String, endpointPath: String): Outcome {
        val prefix = endpointPath.trimEnd('/') + "/"
        if (!rawPath.startsWith(prefix)) {
            return Outcome.Rejected("엔드포인트 경로($endpointPath) 뒤에 식별자가 없다: $rawPath")
        }

        val segment = rawPath.substring(prefix.length)
        if (segment.isEmpty()) return Outcome.Rejected("스테이션 식별자가 비어 있다")
        if ('/' in segment) return Outcome.Rejected("식별자 뒤에 경로가 더 있다: $segment")

        val decoded = decode(segment) ?: return Outcome.Rejected("퍼센트 인코딩이 깨졌다: $segment")

        validate(decoded)?.let { return Outcome.Rejected(it) }

        // MVP 는 경로에서만 얻는다. 그래서 authMethod 가 NONE 이다 — 확인한 적이 없다는
        // 사실 자체를 값으로 남긴다.
        return Outcome.Identified(StationPrincipal(decoded, AuthMethod.NONE))
    }

    /**
     * 퍼센트 디코딩. 깨진 인코딩이면 `null`.
     *
     * [URLDecoder] 는 `application/x-www-form-urlencoded` 규칙이라 `+` 를 공백으로 바꾼다.
     * **URL 경로에서 `+` 는 그냥 `+` 다.** 그래서 리터럴 `+` 를 먼저 `%2B` 로 바꿔 넣어
     * 원래 문자로 되돌아오게 한다. 이미 `%2B` 로 인코딩되어 온 `+` 도 같은 결과가 된다.
     */
    fun decode(segment: String): String? = try {
        URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 규칙 위반이면 사유를, 통과하면 `null`. */
    fun validate(stationId: String): String? = when {
        stationId.isBlank() -> "스테이션 식별자가 비어 있다"

        // 길이는 **디코딩된** 문자 수로 센다. identifierString 의 길이지 URL 의 길이가 아니다.
        stationId.length > MAX_LENGTH ->
            "스테이션 식별자가 ${MAX_LENGTH}자를 넘는다 (${stationId.length}자, Part 4 §3.1.1)"

        // basic 인증의 사용자명 구분자다 (Part 4 §3.1.1).
        ':' in stationId -> "스테이션 식별자에 콜론을 쓸 수 없다 (Part 4 §3.1.1)"

        // 제어문자는 identifierString 이 아니다. 로그·헤더로 흘러 들어가면 곤란하기도 하다.
        stationId.any { it.isISOControl() } -> "스테이션 식별자에 제어문자가 있다"

        else -> null
    }
}
