package dev.swapve.csms.api

import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapRequestId

/**
 * 교환 상관키의 URL 표현 — `"{stationId}:{requestId}"`.
 *
 * ### 왜 합성 식별자인가
 *
 * 교환은 `(stationId, requestId)` **복합키**로만 유일하다. `requestId` 는 스테이션
 * 범위에서만 유일하므로 단독으로는 교환을 가리키지 못한다. 그런데 앱 계약은
 * `GET /api/swaps/{id}` 한 자리를 약속했다. 둘을 다 지키려면 복합키를 **한 토큰으로 인코딩**해야
 * 한다.
 *
 * ### 콜론이 안전한 이유 — 우연이 아니라 프로토콜 규칙이다
 *
 * Part 4 §3.1.1 이 **스테이션 식별자에 콜론을 금지**한다 (basic 인증의 사용자명으로도 쓰이기
 * 때문이다). 그 규칙은 산문이 아니라 실행되는 검사다 — `StationIdentity` 가 핸드셰이크에서
 * 콜론이 든 식별자를 **거절**하므로, 이 CSMS 에 붙어 있는 어떤 스테이션의 식별자에도 콜론이
 * 들어 있을 수 없다. 그래서 첫 콜론 하나로 자르면 모호성이 없다.
 *
 * 구분자를 새로 발명하거나(`__`) 별도의 대리 키를 발번하지 않은 이유가 그것이다. 대리 키를
 * 두면 그것을 어딘가에 저장해야 하고, 그 순간 이벤트 로그에서 재구성할 수 없는 상태가
 * 하나 생긴다.
 */
object SwapIds {

    private const val SEPARATOR = ':'

    /** 상관키를 URL 토큰으로. */
    fun of(key: SwapKey): String = "${key.stationId.value}$SEPARATOR${key.requestId.value}"

    /** 이 교환의 조회 경로. 응답의 `self` 와 `Location` 헤더가 같은 값을 쓴다. */
    fun pathOf(key: SwapKey): String = "/api/swaps/${of(key)}"

    /**
     * URL 토큰을 상관키로. 모양이 아니면 `null`.
     *
     * **예외를 던지지 않는다.** 호출자가 이것을 400 이 아니라 **404** 로 옮기기 때문이다 —
     * 문법이 틀린 식별자든 존재하지 않는 식별자든, 앱 입장에서는 *"그런 교환은 없다"* 로
     * 똑같이 읽히는 것이 맞다. 어떤 문자열이 유효한 교환 식별자인지를 오류 응답으로
     * 알려 주면 그 자체가 스테이션 식별자 열거 수단이 된다.
     */
    fun parse(id: String): SwapKey? {
        val separator = id.indexOf(SEPARATOR)
        if (separator <= 0) return null

        val stationId = id.substring(0, separator)
        if (stationId.isBlank()) return null

        val requestId = id.substring(separator + 1).toIntOrNull() ?: return null
        return SwapKey(StationId(stationId), SwapRequestId(requestId))
    }
}
