package dev.swapve.station

import dev.swapve.swap.IdToken

/**
 * 교환 순서 (PLAN §4.6, Part 2 S03 Remark).
 *
 * > *"Battery swap stations usually operate as 'old battery in first, new battery out second'.
 * > The described mechanism also works, however, when the order is reversed. If that is the case,
 * > this must be reported as `BatterySwapCtrlr.SwapOrder = "Out-In"`."*
 *
 * **두 순서 모두 표준이다.** Part 6 의 재사용 상태 진입 조건이 이 값으로 갈리므로
 * (`EVConnectedPreSessionBatterySwapping` / `EVDisconnectedBatterySwapping`), 시뮬레이터가
 * 이 분기를 그대로 구현하면 §4.6 양방향이 자동으로 검증된다 (PLAN §7.2).
 *
 * @param wireValue 디바이스 모델 변수 `BatterySwapCtrlr.SwapOrder` 로 보고할 값.
 *   [IN_OUT] 은 기본값이라 보고하지 않아도 된다 (S03.FR.07).
 *   ⚠️ **아직 실제로 보고하지는 않는다.** 변수 보고는 `GetVariables`/`GetBaseReport` 를
 *   요구하는데 디바이스 모델 자체가 아직 없다 (BACKLOG B03, `TC_S_104_CS`). 지금 이 값은
 *   시뮬레이터가 어느 순서로 움직일지를 정할 뿐이고, 그 순서는 오가는 메시지의 순서로
 *   드러난다 — CSMS 의 상태머신은 어차피 순서를 몰라야 한다 (PLAN §4.6).
 *   ⚠️ 이 변수는 **부록 CSV(`dm_components_vars.csv`)에 없고 Part 2 본문에만 있다.**
 *   부록은 릴리스와 별개로 갱신될 수 있으므로 본문을 따른다 (PLAN §4.9 주의 3).
 */
enum class SwapOrder(val wireValue: String) {

    /** 헌 배터리를 먼저 넣고 새 배터리를 나중에 가져간다. 기본값. */
    IN_OUT("In-Out"),

    /** 새 배터리를 먼저 가져가고 헌 배터리를 나중에 넣는다. */
    OUT_IN("Out-In"),
}

/**
 * 배터리 한 개.
 *
 * `soC`/`soH` 는 교환 시점에 관측된 값이고, **양쪽(들어온 것·나간 것) 다 보존된다**
 * (PLAN §11.2). 여기서는 시뮬레이터가 그 값을 만들어 낼 뿐이다.
 */
data class SimBattery(
    val serialNumber: String,
    val soC: Double,
    val soH: Double,
) {
    init {
        require(serialNumber.isNotBlank()) { "배터리 일련번호가 비어 있다" }
        require(soC in 0.0..100.0) { "soC 는 0..100 이어야 한다: $soC" }
        require(soH in 0.0..100.0) { "soH 는 0..100 이어야 한다: $soH" }
    }
}

/**
 * 슬롯 하나의 초기 구성. 슬롯 1개 = EVSE 1개다 (PLAN §4.1).
 *
 * [battery] 가 `null` 이면 빈 슬롯이고, 있으면 교환에 내줄 수 있는 배터리가 들어 있다.
 * 프로토콜의 `Available`/`Occupied` 로 바꾸는 일은 경계에서만 한다 (PLAN §4.2).
 *
 * @param connectorId 슬롯 안의 커넥터 번호. `TransactionEvent.evse.connectorId` 가 있어야
 *   한다는 Part 6 Tool validation 때문에 값으로 들고 있는다.
 */
data class SlotConfig(
    val slotId: Int,
    val battery: SimBattery? = null,
    val connectorId: Int = 1,
) {
    init {
        // EVSE id 0 은 "충전소 전체"를 가리키는 예약값이라 슬롯이 될 수 없다 (Part 2 §K).
        require(slotId >= 1) { "슬롯 번호는 1 이상이어야 한다: $slotId" }
        require(connectorId >= 1) { "커넥터 번호는 1 이상이어야 한다: $connectorId" }
    }
}

/**
 * 시뮬레이터 구성 전부.
 *
 * ### 배터리는 세트 단위다 (PLAN §10 결정 #6)
 *
 * [insertSlots] 와 [dispenseSlots] 가 리스트인 이유다. 공식 적합성 시험 `TC_S_103_CSMS` 가
 * **배터리 2개**를 한 번에 넣고 2개를 한 번에 빼며, 입고 슬롯(A·B)과 출고 슬롯(C·D)이
 * 서로 다르다 (PLAN §7.1). 단일 교환만 지원하면 적합성 미달이다.
 *
 * ### requestId 는 스테이션이 발번한다
 *
 * S01 로컬 인가에서는 상관 번호를 스테이션이 정하고 (PLAN §4.4), 입고와 출고가 **같은 값을
 * 승계한다(SHALL)** (S02.FR.02). 그래서 설정값 하나가 교환 1건 전체를 묶는다.
 *
 * @param csmsUrl 스테이션 식별자를 **뺀** 엔드포인트. 실제 연결 URL 은 여기에 `/` +
 *   [stationId] 가 붙는다 (Part 4 §3.1.1). 예: `ws://localhost:8080/ocpp`
 * @param idToken 교환 인가에 쓰는 토큰 (S01).
 * @param chargingIdToken 충전 트랜잭션에 싣는 대체 토큰 — 디바이스 모델의
 *   `BatterySwapCtrlr.IdToken` (S04.FR.02/03). `null` 이면 인가 없는 트랜잭션으로 보고한다:
 *   `type = NoAuthorization`, `idToken = ""` (Part 6 Tool validation).
 * @param insertSlots 이용자가 헌 배터리를 넣을 빈 슬롯. [incomingBatteries] 와 순서대로 짝짓는다.
 * @param dispenseSlots 새 배터리를 내줄, 배터리가 들어 있는 슬롯.
 */
data class StationSimConfig(
    val csmsUrl: String,
    val stationId: String,
    val slots: List<SlotConfig>,
    val idToken: IdToken,
    val requestId: Int,
    val insertSlots: List<Int>,
    val dispenseSlots: List<Int>,
    val incomingBatteries: List<SimBattery>,
    val swapOrder: SwapOrder = SwapOrder.IN_OUT,
    val chargingIdToken: String? = null,
    val vendorName: String = "SwapVe",
    val model: String = "SwapVe-Sim",
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
    val bootReason: String = "PowerUp",
) {
    init {
        require(stationId.isNotBlank()) { "stationId 가 비어 있다" }
        require(slots.isNotEmpty()) { "슬롯이 하나도 없는 스테이션" }
        require(slots.map { it.slotId }.toSet().size == slots.size) { "슬롯 번호가 중복됐다" }

        val known = slots.associateBy { it.slotId }
        require(insertSlots.all { known[it]?.battery == null }) {
            "투입 슬롯은 비어 있어야 한다 (Part 6 AuthorizedBatterySwapping 전제조건): $insertSlots"
        }
        require(dispenseSlots.all { known[it]?.battery != null }) {
            "반출 슬롯에는 배터리가 있어야 한다: $dispenseSlots"
        }
        require(insertSlots.none { it in dispenseSlots }) { "투입 슬롯과 반출 슬롯이 겹친다" }
        require(insertSlots.size == incomingBatteries.size) {
            "투입 슬롯 ${insertSlots.size} 개에 배터리 ${incomingBatteries.size} 개"
        }
        // COMPLETED 불변식 — 들어온 배터리 수 = 나간 배터리 수 (PLAN §5.3).
        // 상태머신이 이것을 검사하지만, 애초에 어긋난 시나리오를 만들어 놓고
        // "상태머신이 막았다"를 확인하는 것은 M7 의 일이지 여기가 아니다.
        require(insertSlots.size == dispenseSlots.size) {
            "장부가 맞지 않는 시나리오: 투입 ${insertSlots.size} 개, 반출 ${dispenseSlots.size} 개"
        }
    }

    /** 연결 URL — `{csmsUrl}/{stationId}` (Part 4 §3.1.1). */
    val connectUrl: String get() = csmsUrl.trimEnd('/') + "/" + stationId
}
