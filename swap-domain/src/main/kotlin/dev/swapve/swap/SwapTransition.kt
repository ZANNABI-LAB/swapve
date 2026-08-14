package dev.swapve.swap

/**
 * 상태 전이 시도의 결과.
 *
 * 성공·무시·이상을 **구분해서 돌려준다.** 예외로 흐름을 제어하지 않는다 — 규약 위반도
 * 정상 응답 대상이고 (PLAN §5.4: *"모든 위반은 CALLRESULT 로 정상 응답한다"*), 무엇을
 * 기록하고 무엇을 회신할지는 상위 계층의 정책이다. 여기서는 판정까지만 한다.
 *
 * 세 결과 모두 [state] 를 갖는다. 호출자는 결과 종류를 보든 안 보든 [state] 를 다음 상태로
 * 쓰면 된다 — [Ignored] 와 [Anomaly] 는 이전 상태를 그대로 돌려준다.
 */
sealed interface SwapTransition {

    /** 이 전이 이후의 상태. */
    val state: SwapTransaction

    /** 전이했다. */
    data class Advanced(override val state: SwapTransaction) : SwapTransition

    /**
     * 멱등 무시했다. 장부는 변하지 않는다 (PLAN §5.4 F4/F6).
     *
     * 실패가 아니다. 재전송·중복 수신은 정상적으로 일어나는 일이고, 두 번 반영되지 않는 것이
     * 올바른 동작이다.
     */
    data class Ignored(
        override val state: SwapTransaction,
        val reason: IgnoreReason,
    ) : SwapTransition

    /**
     * 규약을 벗어난 사건이다. 상태는 그대로 두고 **사실만 기록한다** (PLAN §5.4 F5).
     *
     * 상태머신은 여기서 폭발하지 않는다. 상위 계층은 이상 이벤트로 남기되 응답은 정상 회신한다.
     */
    data class Anomaly(
        override val state: SwapTransaction,
        val reason: AnomalyReason,
        val description: String,
    ) : SwapTransition
}

/** 무시한 이유. */
enum class IgnoreReason {

    /** 같은 상관키로 입고가 다시 왔다 (PLAN §5.4 F4). */
    DUPLICATE_BATTERY_IN,

    /** 같은 상관키로 출고가 다시 왔다. */
    DUPLICATE_BATTERY_OUT,

    /** 이미 인가된 교환에 인가가 또 왔다 (재접속 중 재전송 — PLAN §5.4 F6). */
    DUPLICATE_AUTHORIZATION,

    /** 이미 끝난 교환에 사건이 왔다. */
    ALREADY_TERMINAL,
}

/** 이상으로 판정한 이유. */
enum class AnomalyReason {

    /** 인가 없이 교환 사건이 도착했다 (PLAN §5.4 F5). */
    NOT_AUTHORIZED,

    /** 진행 중인 교환과 상관키가 다른 사건이 도착했다. */
    KEY_MISMATCH,

    /** 들어온 배터리 수와 나간 배터리 수가 맞지 않는다 (PLAN §5.3 COMPLETED 불변식). */
    BATTERY_COUNT_MISMATCH,

    /** 수령 타임아웃이 올 수 없는 상태에서 도착했다. */
    UNEXPECTED_TIMEOUT,
}
