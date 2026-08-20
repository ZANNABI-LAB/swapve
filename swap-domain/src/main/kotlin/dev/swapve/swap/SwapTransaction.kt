package dev.swapve.swap

import java.time.Instant

/**
 * 교환 트랜잭션의 상태.
 *
 * ```
 *                 ┌──────┐
 *                 │ IDLE │
 *                 └──┬───┘
 *          Authorized│
 *              ┌─────▼──────┐
 *              │ AUTHORIZED │
 *              └──┬──────┬──┘
 *       BatteryIn │      │ BatteryOut
 *            ┌────▼──┐ ┌─▼───────┐
 *            │HALF_IN│ │HALF_OUT │
 *            └─┬───┬─┘ └────┬────┘
 *  BatteryOut  │   │ Timeout│ BatteryIn
 *      ┌───────▼┐ ┌▼────────────┐ ┌────────▼┐
 *      │COMPLETED│ │OUT_TIMED_OUT│ │COMPLETED│
 *      └─────────┘ └─────────────┘ └─────────┘
 * ```
 *
 * **순서 불가지론이다**. 입고 먼저가 통상이지만 역순도 표준이고, 두 경로 모두
 * [Completed] 에 도달한다. 어느 순서였는지는 상태 자체에 남지 않는다 — 그건 스테이션 설정이지
 * 교환의 성질이 아니다.
 *
 * 반쪽이 열린 상태를 [HalfIn] / [HalfOut] 두 타입으로 나눈 것은 "반쪽 교환이 정확히 1개
 * 열려 있다"는 불변식 을 타입으로 못박기 위해서다 — 입고와 출고가 동시에 열린
 * 상태는 표현할 수 없다.
 *
 * 반납 타임아웃에 해당하는 만료 상태는 **없다.** CSMS 는 그 상황을 통보받지 못한다.
 */
sealed interface SwapTransaction {

    /** 아직 인가되지 않았다. 이 상태에는 상관키가 없다. */
    data object Idle : SwapTransaction

    /**
     * 인가가 났고 상관키가 확정됐다.
     *
     * 이 상태에서 배터리를 넣지 않은 채 시간이 지나면 스테이션이 자체 종료하고 CSMS 는
     * 아무 통보도 받지 못한다. CSMS 측 정리 타이머는 **표준 동작이 아니라
     * 운영 편의**이고, 상태머신 밖(상위 계층)의 일이다.
     */
    data class Authorized(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
    ) : SwapTransaction

    /** 헌 배터리가 들어왔고 새 배터리는 아직 안 나갔다. */
    data class HalfIn(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesIn: List<BatteryData>,
        val inAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesIn.isNotEmpty()) { "입고 반쪽에 배터리가 없다: $key" }
        }
    }

    /** 새 배터리가 나갔고 헌 배터리는 아직 안 들어왔다. */
    data class HalfOut(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesOut: List<BatteryData>,
        val outAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesOut.isNotEmpty()) { "출고 반쪽에 배터리가 없다: $key" }
        }
    }

    /**
     * 교환이 균형 있게 끝났다.
     *
     * 양쪽 배터리의 일련번호·[BatteryData.soC]·[BatteryData.soH] 를 **둘 다 보존한다**
     *. [startedAt] / [completedAt] 도 남긴다. 요금 계산은 여기서 하지 않는다 —
     * 나중에 이 레코드 위의 순수 계산으로 붙는다.
     *
     * "들어온 배터리 수 = 나간 배터리 수" 불변식 을 생성자에서 막는다.
     * 수량이 안 맞는 [Completed] 는 존재할 수 없다.
     */
    data class Completed(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val batteriesIn: List<BatteryData>,
        val batteriesOut: List<BatteryData>,
        val startedAt: Instant,
        val completedAt: Instant,
    ) : SwapTransaction {
        init {
            require(batteriesIn.size == batteriesOut.size) {
                "장부 불균형: 입고 ${batteriesIn.size} 개, 출고 ${batteriesOut.size} 개 ($key)"
            }
            require(batteriesIn.isNotEmpty()) { "배터리 없는 교환: $key" }
        }

        /** 교환된 배터리 세트의 크기. */
        val batteryCount: Int get() = batteriesIn.size
    }

    /**
     * 제공된 배터리를 이용자가 꺼내가지 않아 교환이 반쪽으로 끝났다 (S03.FR.06).
     *
     * > *"Situation needs to be reported, because CSMS ends up with an **orphan BatteryIn for
     * > which a BatteryOut is missing**."*
     *
     * 그래서 [orphanBatteriesIn] 이 상태에 남는다. 장부 불균형이 기록으로 읽혀야 보상이 가능하다.
     */
    data class OutTimedOut(
        val key: SwapKey,
        val idToken: IdToken,
        val authorizedAt: Instant,
        val orphanBatteriesIn: List<BatteryData>,
        val startedAt: Instant,
        val timedOutAt: Instant,
    ) : SwapTransaction {
        init {
            require(orphanBatteriesIn.isNotEmpty()) { "orphan 없는 수령 타임아웃: $key" }
        }

        /** 대응하는 출고가 없는 입고 배터리 수. 항상 양수다 — 그게 이 상태의 존재 이유다. */
        val ledgerImbalance: Int get() = orphanBatteriesIn.size
    }
}

/**
 * 이 상태의 상관키. [SwapTransaction.Idle] 만 `null` 이다 — 아직 확정되지 않았다.
 */
val SwapTransaction.key: SwapKey?
    get() = when (this) {
        is SwapTransaction.Idle -> null
        is SwapTransaction.Authorized -> key
        is SwapTransaction.HalfIn -> key
        is SwapTransaction.HalfOut -> key
        is SwapTransaction.Completed -> key
        is SwapTransaction.OutTimedOut -> key
    }

/** 더 이상 전이하지 않는 상태인가. */
val SwapTransaction.isTerminal: Boolean
    get() = this is SwapTransaction.Completed || this is SwapTransaction.OutTimedOut
