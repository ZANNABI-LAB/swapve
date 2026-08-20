package dev.swapve.csms.swap

import dev.swapve.swap.SlotId
import dev.swapve.swap.SlotState
import dev.swapve.swap.StationId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 관측된 슬롯 상태 한 건. 언제 관측했는지까지 남긴다. */
data class ObservedSlotState(
    val state: SlotState,
    val observedAt: Instant,
)

/**
 * 스테이션이 알려 온 슬롯 점유 상태 (S03.FR.02/04).
 *
 * ### 여기가 반전을 푸는 자리다
 *
 * `NotifyEventRequest` 는 `Available`/`Occupied` 로 말하고, 그 의미는 직관과 반대다
 *. 도메인은 [SlotState.EMPTY]/[SlotState.HOLDS_BATTERY] 로 말한다. 변환 자체는
 * `ocpp-core` 의 `AvailabilityState` 한 곳에만 있고, 이 클래스는 그 결과를 도메인 열거형으로
 * 옮겨 담을 뿐이다 — 여기에는 `"Occupied"` 라는 문자열이 없다.
 *
 * ### `Slot`/`Station` 도메인 객체를 만들지 않는 이유
 *
 * `swap-domain` 의 `Slot` 은 `HOLDS_BATTERY` 면 배터리 정보가 반드시 있어야 한다는 불변식을
 * 갖는다. 그런데 **`NotifyEvent` 는 일련번호도 SoC 도 싣지 않는다** — 슬롯이 찼다는 사실만
 * 말한다. 없는 배터리 정보를 지어내 `Slot` 을 만들면 그 불변식이 거짓말이 된다.
 *
 * 배터리 상세는 이미 다른 곳에 온전히 있다. 교환 트랜잭션이 입고·출고 양쪽의
 * `serialNumber`·`soC`·`soH` 를 보존하기 때문이다. 그래서 여기서는 **관측한
 * 사실만** 담는다. 정보를 지어내지도, 버리지도 않는다.
 *
 * 스레드 안전하다.
 */
@Component
class SlotStateRegistry {

    private val states = ConcurrentHashMap<Pair<StationId, SlotId>, ObservedSlotState>()

    /** 관측을 기록한다. 같은 슬롯의 이전 관측은 대체된다. */
    fun observe(stationId: StationId, slotId: SlotId, state: SlotState, at: Instant) {
        states[stationId to slotId] = ObservedSlotState(state, at)
    }

    /** 마지막으로 관측된 상태. 한 번도 알려 오지 않은 슬롯이면 `null` — 모른다는 뜻이다. */
    fun stateOf(stationId: StationId, slotId: SlotId): SlotState? = states[stationId to slotId]?.state

    /** 이 스테이션에서 관측된 슬롯 전부. */
    fun of(stationId: StationId): Map<SlotId, ObservedSlotState> =
        states.entries
            .filter { it.key.first == stationId }
            .associate { it.key.second to it.value }

    /** 배터리가 들어 있는 것으로 관측된 슬롯 수. 재고 판정이 아니라 관측 요약이다. */
    fun slotsHoldingBattery(stationId: StationId): Int =
        of(stationId).count { it.value.state == SlotState.HOLDS_BATTERY }
}
