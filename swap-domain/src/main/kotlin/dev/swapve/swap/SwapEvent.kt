package dev.swapve.swap

import java.time.Instant

/**
 * 교환 트랜잭션에 입력되는 사건.
 *
 * 모든 사건이 [key] 와 [at] 을 갖는다. 시각을 이벤트에 실어 주입받으므로 상태 전이가
 * 현재 시각을 조회할 필요가 없다 — 같은 입력이면 언제 돌려도 같은 결과다.
 *
 * 배터리는 **항상 리스트**다. 공식 적합성 시험이 배터리 2개 세트로 교환한다
 * (PLAN §7.1 `TC_S_103_CSMS`, §10 결정 #6). 단일 배터리만 지원하면 적합성 미달이다.
 */
sealed interface SwapEvent {

    /** 이 사건이 속한 교환 (PLAN §5.3 복합키). */
    val key: SwapKey

    /** 사건이 관측된 시각. */
    val at: Instant

    /**
     * 인가가 났다. 로컬 개시(스테이션 RFID)든 원격 개시(CSMS 발신)든 도메인에는 같은 사건이다
     * (PLAN §4.4 S01/S02). 어느 쪽이 상관 번호를 발번했는지는 경계 계층의 관심사다.
     */
    data class Authorized(
        override val key: SwapKey,
        val idToken: IdToken,
        override val at: Instant,
    ) : SwapEvent

    /** 이용자가 헌 배터리를 **넣었다.** */
    data class BatteryIn(
        override val key: SwapKey,
        val idToken: IdToken,
        val batteries: List<BatteryData>,
        override val at: Instant,
    ) : SwapEvent {
        init {
            require(batteries.isNotEmpty()) { "배터리 없는 입고 사건" }
        }
    }

    /** 이용자가 새 배터리를 **가져갔다.** */
    data class BatteryOut(
        override val key: SwapKey,
        val idToken: IdToken,
        val batteries: List<BatteryData>,
        override val at: Instant,
    ) : SwapEvent {
        init {
            require(batteries.isNotEmpty()) { "배터리 없는 출고 사건" }
        }
    }

    /**
     * 제공된 배터리를 **꺼내가지 않았다** (PLAN §4.7 S03.FR.06).
     *
     * 두 종류의 타임아웃 중 CSMS 가 통보받는 쪽이다. 인가 후 배터리를 넣지 않은 타임아웃은
     * 스테이션이 자체 종료하고 CSMS 에 아무것도 보내지 않으므로 사건 자체가 없다.
     */
    data class BatteryOutTimeout(
        override val key: SwapKey,
        override val at: Instant,
    ) : SwapEvent
}
