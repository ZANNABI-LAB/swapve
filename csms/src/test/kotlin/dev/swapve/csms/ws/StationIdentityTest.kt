package dev.swapve.csms.ws

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 연결 URL 에서 스테이션 식별자를 꺼내는 규칙 (Part 4 Edition 2 §3.1.1).
 *
 * 서버를 띄우지 않는다. 판정이 순수 함수라서 가능한 일이고, 그래서 경계 조건을 값싸게
 * 전부 시험할 수 있다. 같은 규칙이 실제 핸드셰이크에서도 지켜지는지는
 * `WebSocketHandshakeTest` 가 별도로 확인한다.
 */
class StationIdentityTest {

    private val endpoint = "/ocpp"

    @Test
    fun `평범한 식별자를 꺼낸다`() {
        val outcome = StationIdentity.fromRawPath("/ocpp/CS001", endpoint)

        val identified = assertIs<StationIdentity.Outcome.Identified>(outcome)
        assertEquals("CS001", identified.principal.stationId)
    }

    @Test
    fun `MVP 는 경로에서만 신원을 얻으므로 authMethod 가 NONE 이다`() {
        val outcome = StationIdentity.fromRawPath("/ocpp/CS001", endpoint)

        val identified = assertIs<StationIdentity.Outcome.Identified>(outcome)
        assertEquals(AuthMethod.NONE, identified.principal.authMethod)
    }

    @Test
    fun `퍼센트 인코딩된 식별자를 복원한다`() {
        // 스펙의 예시 그대로다: RDAM|123 → RDAM%7C123
        val outcome = StationIdentity.fromRawPath("/ocpp/RDAM%7C123", endpoint)

        val identified = assertIs<StationIdentity.Outcome.Identified>(outcome)
        assertEquals("RDAM|123", identified.principal.stationId)
    }

    @Test
    fun `경로의 플러스는 공백이 아니라 플러스다`() {
        // URLDecoder 의 기본 동작(form-urlencoded)을 그대로 쓰면 여기서 "CS 001" 이 된다.
        val outcome = StationIdentity.fromRawPath("/ocpp/CS+001", endpoint)

        val identified = assertIs<StationIdentity.Outcome.Identified>(outcome)
        assertEquals("CS+001", identified.principal.stationId)
    }

    @Test
    fun `48자는 통과하고 49자는 거절된다`() {
        val exactly48 = "C".repeat(48)
        val tooLong = "C".repeat(49)

        assertIs<StationIdentity.Outcome.Identified>(StationIdentity.fromRawPath("/ocpp/$exactly48", endpoint))
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/$tooLong", endpoint))
    }

    @Test
    fun `콜론이 든 식별자는 거절된다`() {
        // basic 인증의 사용자명 구분자다 (Part 4 §3.1.1).
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/CS:001", endpoint))
    }

    @Test
    fun `퍼센트 인코딩된 콜론도 거절된다`() {
        // 디코딩이 먼저, 검증이 나중이 아니면 %3A 가 검사를 그대로 통과한다.
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/CS%3A001", endpoint))
    }

    @Test
    fun `디코딩한 결과가 48자를 넘으면 거절된다`() {
        // URL 길이가 아니라 identifierString 길이를 센다는 것을 고정한다.
        val encoded = "%7C".repeat(49)

        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/$encoded", endpoint))
    }

    @Test
    fun `식별자가 없으면 거절된다`() {
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/", endpoint))
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp", endpoint))
    }

    @Test
    fun `식별자 뒤에 경로가 더 있으면 거절된다`() {
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/CS001/extra", endpoint))
    }

    @Test
    fun `엔드포인트 경로가 다르면 거절된다`() {
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/other/CS001", endpoint))
    }

    @Test
    fun `깨진 퍼센트 인코딩은 거절된다`() {
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/CS%ZZ", endpoint))
    }

    @Test
    fun `제어문자가 든 식별자는 거절된다`() {
        assertIs<StationIdentity.Outcome.Rejected>(StationIdentity.fromRawPath("/ocpp/CS%00001", endpoint))
    }

    @Test
    fun `설정된 엔드포인트 경로를 따른다`() {
        // 스펙의 두 번째 예시가 /ocppj 를 쓴다. 경로는 설정값이지 상수가 아니다.
        val outcome = StationIdentity.fromRawPath("/ocppj/RDAM%7C123", "/ocppj")

        val identified = assertIs<StationIdentity.Outcome.Identified>(outcome)
        assertEquals("RDAM|123", identified.principal.stationId)
    }

    @Test
    fun `검증 규칙은 통과하면 사유가 없다`() {
        assertNull(StationIdentity.validate("CS001"))
    }
}
