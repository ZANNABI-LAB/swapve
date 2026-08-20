package dev.swapve.swap

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 교환 상태머신 시험.
 *
 * 배터리 2개 세트로 시험하는 이유는 공식 적합성 케이스 `TC_S_103_CSMS` 가 그렇게 시험하기
 * 때문이다. 입고 슬롯(A,B)과 출고 슬롯(C,D)이 다른 것도 그 시퀀스 그대로다.
 */
class SwapStateMachineTest {

    private val station = StationId("KR-SEOUL-001")
    private val key = SwapKey(station, SwapRequestId(42))
    private val token = IdToken("049A1B2C3D", "ISO14443")

    private val t0 = Instant.parse("2026-08-14T09:00:00Z")
    private val t1 = Instant.parse("2026-08-14T09:00:10Z")
    private val t2 = Instant.parse("2026-08-14T09:00:40Z")

    // TC_S_103_CSMS Step 11 — 입고 슬롯 A(0), B(1)
    private val oldBatteries = listOf(
        BatteryData(SlotId(0), "1234", soC = 23.0, soH = 85.0),
        BatteryData(SlotId(1), "5678", soC = 45.0, soH = 87.0),
    )

    // TC_S_103_CSMS Step 21 — 출고 슬롯 C(2), D(3)
    private val newBatteries = listOf(
        BatteryData(SlotId(2), "4321", soC = 80.0, soH = 95.0),
        BatteryData(SlotId(3), "8765", soC = 85.0, soH = 78.0),
    )

    private fun authorized(at: Instant = t0) = SwapEvent.Authorized(key, token, at)
    private fun batteryIn(at: Instant = t1) = SwapEvent.BatteryIn(key, token, oldBatteries, at)
    private fun batteryOut(at: Instant = t2) = SwapEvent.BatteryOut(key, token, newBatteries, at)

    @Test
    fun `입고 먼저 순서로 배터리 2개 세트 교환이 완료된다`() {
        val state = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryIn(), batteryOut()),
        )

        val completed = assertIs<SwapTransaction.Completed>(state)
        assertEquals(2, completed.batteryCount)
        assertEquals(key, completed.key)
    }

    @Test
    fun `출고 먼저 순서로도 같은 상태머신이 완료에 도달한다`() {
        // 역순도 표준이다. SwapOrder 를 상태머신이 알 필요가 없다.
        val state = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryOut(t1), batteryIn(t2)),
        )

        val completed = assertIs<SwapTransaction.Completed>(state)
        assertEquals(2, completed.batteryCount)
        assertEquals(oldBatteries, completed.batteriesIn)
        assertEquals(newBatteries, completed.batteriesOut)
    }

    @Test
    fun `두 순서가 같은 완료 레코드를 만든다`() {
        val inFirst = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryIn(t1), batteryOut(t2)),
        )
        val outFirst = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryOut(t1), batteryIn(t2)),
        )

        assertEquals(inFirst, outFirst, "교환 순서가 완료 레코드에 남으면 안 된다")
    }

    @Test
    fun `완료 시점에 들어온 수와 나간 수가 같다`() {
        val completed = assertIs<SwapTransaction.Completed>(
            SwapStateMachine.replay(SwapTransaction.Idle, listOf(authorized(), batteryIn(), batteryOut())),
        )

        assertEquals(completed.batteriesIn.size, completed.batteriesOut.size)
    }

    @Test
    fun `수량이 맞지 않으면 완료되지 않고 이상으로 기록된다`() {
        val halfIn = SwapStateMachine.transition(SwapTransaction.Idle, authorized()).state
            .let { SwapStateMachine.transition(it, batteryIn()).state }
        val oneOut = SwapEvent.BatteryOut(key, token, newBatteries.take(1), t2)

        val result = SwapStateMachine.transition(halfIn, oneOut)

        val anomaly = assertIs<SwapTransition.Anomaly>(result)
        assertEquals(AnomalyReason.BATTERY_COUNT_MISMATCH, anomaly.reason)
        assertEquals(halfIn, anomaly.state, "이상이어도 장부는 그대로다")
    }

    @Test
    fun `완료 레코드에서 양쪽 배터리의 일련번호와 SoC 와 SoH 를 모두 읽을 수 있다`() {
        // 이걸 버리면 나중에 과금이 스키마 변경 없이는 불가능해진다.
        val completed = assertIs<SwapTransaction.Completed>(
            SwapStateMachine.replay(SwapTransaction.Idle, listOf(authorized(), batteryIn(), batteryOut())),
        )

        assertEquals(listOf("1234", "5678"), completed.batteriesIn.map { it.serialNumber })
        assertEquals(listOf("4321", "8765"), completed.batteriesOut.map { it.serialNumber })
        assertEquals(listOf(23.0, 45.0), completed.batteriesIn.map { it.soC })
        assertEquals(listOf(80.0, 85.0), completed.batteriesOut.map { it.soC })
        assertEquals(listOf(85.0, 87.0), completed.batteriesIn.map { it.soH })
        assertEquals(listOf(95.0, 78.0), completed.batteriesOut.map { it.soH })

        // 시작·종료 시각도 남는다
        assertEquals(t0, completed.authorizedAt)
        assertEquals(t1, completed.startedAt)
        assertEquals(t2, completed.completedAt)
    }

    @Test
    fun `입고 슬롯과 출고 슬롯이 다르다`() {
        val completed = assertIs<SwapTransaction.Completed>(
            SwapStateMachine.replay(SwapTransaction.Idle, listOf(authorized(), batteryIn(), batteryOut())),
        )

        val inSlots = completed.batteriesIn.map { it.slotId }.toSet()
        val outSlots = completed.batteriesOut.map { it.slotId }.toSet()
        assertEquals(emptySet(), inSlots intersect outSlots, "입고 슬롯(A,B)과 출고 슬롯(C,D)은 겹치지 않는다")
    }

    @Test
    fun `같은 상관키로 입고가 재수신되면 멱등 무시되고 장부가 변하지 않는다`() {
        // F4
        val halfIn = SwapStateMachine.replay(SwapTransaction.Idle, listOf(authorized(), batteryIn()))

        val result = SwapStateMachine.transition(halfIn, batteryIn(t2))

        val ignored = assertIs<SwapTransition.Ignored>(result)
        assertEquals(IgnoreReason.DUPLICATE_BATTERY_IN, ignored.reason)
        assertEquals(halfIn, ignored.state)
        assertEquals(2, assertIs<SwapTransaction.HalfIn>(ignored.state).batteriesIn.size, "장부가 두 번 늘면 안 된다")
    }

    @Test
    fun `중복 입고 이후에도 교환은 정상적으로 완료된다`() {
        val state = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryIn(), batteryIn(t2), batteryOut()),
        )

        val completed = assertIs<SwapTransaction.Completed>(state)
        assertEquals(2, completed.batteryCount)
    }

    @Test
    fun `인가 재수신도 멱등 무시된다`() {
        val authorized = SwapStateMachine.transition(SwapTransaction.Idle, authorized()).state

        val result = SwapStateMachine.transition(authorized, authorized(t1))

        val ignored = assertIs<SwapTransition.Ignored>(result)
        assertEquals(IgnoreReason.DUPLICATE_AUTHORIZATION, ignored.reason)
        assertEquals(authorized, ignored.state, "인가 시각이 덮어써지면 안 된다")
    }

    @Test
    fun `서로 다른 스테이션이 같은 상관 번호를 써도 충돌하지 않는다`() {
        // requestId 는 스테이션 범위에서만 유일하다.
        val otherKey = SwapKey(StationId("KR-BUSAN-007"), SwapRequestId(42))

        assertNotEquals(key, otherKey)

        val mine = SwapStateMachine.transition(SwapTransaction.Idle, authorized()).state
        val theirs = SwapStateMachine.transition(
            SwapTransaction.Idle,
            SwapEvent.Authorized(otherKey, token, t0),
        ).state

        assertNotEquals(mine, theirs)
        assertEquals(station, assertIs<SwapTransaction.Authorized>(mine).key.stationId)
    }

    @Test
    fun `진행 중인 교환에 다른 상관키의 사건이 오면 이상으로 기록된다`() {
        val authorized = SwapStateMachine.transition(SwapTransaction.Idle, authorized()).state
        val otherKey = SwapKey(station, SwapRequestId(99))

        val result = SwapStateMachine.transition(
            authorized,
            SwapEvent.BatteryIn(otherKey, token, oldBatteries, t1),
        )

        val anomaly = assertIs<SwapTransition.Anomaly>(result)
        assertEquals(AnomalyReason.KEY_MISMATCH, anomaly.reason)
        assertEquals(authorized, anomaly.state)
    }

    @Test
    fun `인가 없이 교환 사건이 오면 이상으로 판정되지만 예외는 나지 않는다`() {
        // F5 — 응답은 상위 계층이 정상 회신한다.
        val result = SwapStateMachine.transition(SwapTransaction.Idle, batteryIn())

        val anomaly = assertIs<SwapTransition.Anomaly>(result)
        assertEquals(AnomalyReason.NOT_AUTHORIZED, anomaly.reason)
        assertEquals(SwapTransaction.Idle, anomaly.state)
        assertTrue(anomaly.description.isNotBlank())
    }

    @Test
    fun `인가 없이 출고나 수령 타임아웃이 와도 이상으로만 판정된다`() {
        listOf(batteryOut(), SwapEvent.BatteryOutTimeout(key, t2)).forEach { event ->
            val anomaly = assertIs<SwapTransition.Anomaly>(SwapStateMachine.transition(SwapTransaction.Idle, event))
            assertEquals(AnomalyReason.NOT_AUTHORIZED, anomaly.reason)
        }
    }

    @Test
    fun `입고 반쪽에서 수령 타임아웃을 받으면 orphan 이 상태에 남는다`() {
        // S03.FR.06 — CSMS 에 orphan BatteryIn 이 남는다는 사실이 읽혀야 한다.
        val halfIn = SwapStateMachine.replay(SwapTransaction.Idle, listOf(authorized(), batteryIn()))

        val result = SwapStateMachine.transition(halfIn, SwapEvent.BatteryOutTimeout(key, t2))

        val timedOut = assertIs<SwapTransaction.OutTimedOut>(assertIs<SwapTransition.Advanced>(result).state)
        assertEquals(oldBatteries, timedOut.orphanBatteriesIn)
        assertEquals(2, timedOut.ledgerImbalance)
        assertEquals(t2, timedOut.timedOutAt)
        assertEquals(listOf(23.0, 45.0), timedOut.orphanBatteriesIn.map { it.soC }, "보상하려면 SoC 가 필요하다")
    }

    @Test
    fun `끝난 교환에 사건이 더 오면 멱등 무시된다`() {
        // F6 — 재접속 후 재전송.
        val completed = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryIn(), batteryOut()),
        )

        val result = SwapStateMachine.transition(completed, batteryOut(t2))

        val ignored = assertIs<SwapTransition.Ignored>(result)
        assertEquals(IgnoreReason.ALREADY_TERMINAL, ignored.reason)
        assertEquals(completed, ignored.state)
    }

    @Test
    fun `수령 타임아웃 이후에도 상태가 더 변하지 않는다`() {
        val timedOut = SwapStateMachine.replay(
            SwapTransaction.Idle,
            listOf(authorized(), batteryIn(), SwapEvent.BatteryOutTimeout(key, t2)),
        )

        val after = SwapStateMachine.replay(timedOut, listOf(batteryOut(t2), authorized(t2)))

        assertEquals(timedOut, after, "장부 불균형 기록이 덮어써지면 안 된다")
    }

    @Test
    fun `같은 입력이면 항상 같은 결과다`() {
        val events = listOf(authorized(), batteryIn(), batteryIn(t2), batteryOut())

        assertEquals(
            SwapStateMachine.replay(SwapTransaction.Idle, events),
            SwapStateMachine.replay(SwapTransaction.Idle, events),
        )
    }
}
