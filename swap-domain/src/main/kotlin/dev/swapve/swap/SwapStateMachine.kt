package dev.swapve.swap

import java.time.Instant

/**
 * 교환 트랜잭션 상태머신 (PLAN §5.2).
 *
 * `(현재 상태, 사건) -> 전이 결과` 순수 함수 하나다. I/O 도, 전역 상태도, 현재 시각 조회도
 * 없다 — 시각은 사건이 실어 온다. 같은 입력이면 항상 같은 결과다.
 *
 * **순서 불가지론이다** (PLAN §4.6). 입고 먼저와 출고 먼저를 대칭으로 처리하고, 두 경로 모두
 * 같은 [SwapTransaction.Completed] 에 도달한다. 어느 순서로 동작하는 스테이션인지는 이
 * 상태머신이 알 필요가 없다 — 안다면 그건 잘못된 전제를 코드에 박은 것이다.
 *
 * **배터리는 세트 단위다** (PLAN §10 결정 #6). 입고도 출고도 여러 개를 한 번에 받는다.
 */
object SwapStateMachine {

    /**
     * 사건 하나를 적용한다.
     *
     * 예외를 던지지 않는다. 규약을 벗어난 사건은 [SwapTransition.Anomaly] 로, 중복 수신은
     * [SwapTransition.Ignored] 로 돌려준다. 두 경우 모두 상태는 그대로다.
     */
    fun transition(state: SwapTransaction, event: SwapEvent): SwapTransition {
        val current = state.key
        if (current != null && current != event.key) {
            return anomaly(state, AnomalyReason.KEY_MISMATCH, "진행 중인 교환은 $current 인데 ${event.key} 사건이 왔다")
        }
        if (state.isTerminal) {
            return SwapTransition.Ignored(state, IgnoreReason.ALREADY_TERMINAL)
        }

        return when (state) {
            is SwapTransaction.Idle -> fromIdle(state, event)
            is SwapTransaction.Authorized -> fromAuthorized(state, event)
            is SwapTransaction.HalfIn -> fromHalfIn(state, event)
            is SwapTransaction.HalfOut -> fromHalfOut(state, event)
            // 종단 상태는 위에서 걸렀다.
            is SwapTransaction.Completed, is SwapTransaction.OutTimedOut ->
                SwapTransition.Ignored(state, IgnoreReason.ALREADY_TERMINAL)
        }
    }

    /** 여러 사건을 순서대로 적용하고 마지막 상태를 돌려준다. 중간 결과가 필요하면 [transition] 을 직접 쓴다. */
    fun replay(state: SwapTransaction, events: List<SwapEvent>): SwapTransaction =
        events.fold(state) { acc, event -> transition(acc, event).state }

    private fun fromIdle(state: SwapTransaction.Idle, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Advanced(
            SwapTransaction.Authorized(event.key, event.idToken, event.at),
        )

        // F5 순서 위반 — 인가 없이 교환 사건이 왔다. 기록만 하고 상태는 그대로 둔다.
        is SwapEvent.BatteryIn, is SwapEvent.BatteryOut, is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.NOT_AUTHORIZED, "인가되지 않은 교환 ${event.key} 에 ${event.label} 사건이 왔다")
    }

    private fun fromAuthorized(state: SwapTransaction.Authorized, event: SwapEvent): SwapTransition = when (event) {
        // F6 재전송 — 같은 상관키의 인가가 또 왔다.
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        is SwapEvent.BatteryIn -> SwapTransition.Advanced(
            SwapTransaction.HalfIn(state.key, state.idToken, state.authorizedAt, event.batteries, event.at),
        )

        is SwapEvent.BatteryOut -> SwapTransition.Advanced(
            SwapTransaction.HalfOut(state.key, state.idToken, state.authorizedAt, event.batteries, event.at),
        )

        // 아직 오간 배터리가 없으니 수령 타임아웃이 성립하지 않는다. orphan 도 없다.
        is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.UNEXPECTED_TIMEOUT, "오간 배터리가 없는 교환 ${state.key} 에 수령 타임아웃이 왔다")
    }

    private fun fromHalfIn(state: SwapTransaction.HalfIn, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        // F4 중복 입고 — 장부가 두 번 늘면 안 된다.
        is SwapEvent.BatteryIn -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_BATTERY_IN)

        is SwapEvent.BatteryOut -> complete(
            state = state,
            key = state.key,
            idToken = state.idToken,
            authorizedAt = state.authorizedAt,
            batteriesIn = state.batteriesIn,
            batteriesOut = event.batteries,
            startedAt = state.inAt,
            completedAt = event.at,
        )

        // F2 수령 타임아웃 — 들어온 배터리가 orphan 으로 남는다 (PLAN §4.7 S03.FR.06).
        is SwapEvent.BatteryOutTimeout -> SwapTransition.Advanced(
            SwapTransaction.OutTimedOut(
                key = state.key,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                orphanBatteriesIn = state.batteriesIn,
                startedAt = state.inAt,
                timedOutAt = event.at,
            ),
        )
    }

    private fun fromHalfOut(state: SwapTransaction.HalfOut, event: SwapEvent): SwapTransition = when (event) {
        is SwapEvent.Authorized -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_AUTHORIZATION)

        is SwapEvent.BatteryIn -> complete(
            state = state,
            key = state.key,
            idToken = state.idToken,
            authorizedAt = state.authorizedAt,
            batteriesIn = event.batteries,
            batteriesOut = state.batteriesOut,
            startedAt = state.outAt,
            completedAt = event.at,
        )

        is SwapEvent.BatteryOut -> SwapTransition.Ignored(state, IgnoreReason.DUPLICATE_BATTERY_OUT)

        // 배터리는 이미 나갔다. 수령 타임아웃이 뒤늦게 올 자리가 아니다.
        is SwapEvent.BatteryOutTimeout ->
            anomaly(state, AnomalyReason.UNEXPECTED_TIMEOUT, "이미 출고된 교환 ${state.key} 에 수령 타임아웃이 왔다")
    }

    /**
     * 두 반쪽을 합쳐 완료한다. 입고 먼저든 출고 먼저든 같은 자리로 모인다 (PLAN §4.6).
     *
     * 수량이 맞지 않으면 [SwapTransaction.Completed] 를 만들지 않는다. "들어온 수 = 나간 수"
     * 불변식 (PLAN §5.3) 이 구성상 항상 참이 되도록, 불균형은 완료가 아니라 이상으로 뺀다.
     */
    private fun complete(
        state: SwapTransaction,
        key: SwapKey,
        idToken: IdToken,
        authorizedAt: Instant,
        batteriesIn: List<BatteryData>,
        batteriesOut: List<BatteryData>,
        startedAt: Instant,
        completedAt: Instant,
    ): SwapTransition {
        if (batteriesIn.size != batteriesOut.size) {
            return anomaly(
                state,
                AnomalyReason.BATTERY_COUNT_MISMATCH,
                "교환 $key 의 장부가 맞지 않는다: 입고 ${batteriesIn.size} 개, 출고 ${batteriesOut.size} 개",
            )
        }
        return SwapTransition.Advanced(
            SwapTransaction.Completed(
                key = key,
                idToken = idToken,
                authorizedAt = authorizedAt,
                batteriesIn = batteriesIn,
                batteriesOut = batteriesOut,
                startedAt = startedAt,
                completedAt = completedAt,
            ),
        )
    }

    private fun anomaly(state: SwapTransaction, reason: AnomalyReason, description: String) =
        SwapTransition.Anomaly(state, reason, description)

    /** 이상 이벤트 설명에 쓰는 사건 이름. */
    private val SwapEvent.label: String
        get() = when (this) {
            is SwapEvent.Authorized -> "인가"
            is SwapEvent.BatteryIn -> "입고"
            is SwapEvent.BatteryOut -> "출고"
            is SwapEvent.BatteryOutTimeout -> "수령 타임아웃"
        }
}
