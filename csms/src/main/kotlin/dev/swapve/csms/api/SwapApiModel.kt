package dev.swapve.csms.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import dev.swapve.csms.swap.OutTimedOutRecord
import dev.swapve.csms.swap.SwapRejection
import dev.swapve.swap.BatteryData
import dev.swapve.swap.IdToken
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapTransaction
import java.time.Duration
import java.time.Instant

/**
 * 앱이 보내는 교환 시작 요청 — QR 로 얻는 정보 그대로.
 *
 * > **S02 - Battery Swap Remote Start**: *"EV Driver requests CSMS to initiate a battery swap
 * > via a smartphone app, e.g. by scanning a QR code or selecting the appropriate station in
 * > the app."*
 *
 * ### `requestId` 가 없는 것이 의도다
 *
 * S02 의 발번 주체는 **CSMS** 다 (표). 앱이 상관 번호를 정하게 두면 그 규칙이
 * 클라이언트로 새고, 스테이션이 이어지는 `BatterySwapRequest` 에 같은 값을 써야 한다는
 * S02.FR.02 의 책임 소재가 흐려진다. 발번은 `SwapRequestIds` 한 곳에서만 일어난다.
 *
 * ### 생성자 애노테이션을 붙인 이유
 *
 * `jackson-module-kotlin` 을 의존성에 넣지 않았다. 지금 필요한 것은 요청 DTO 둘의 역직렬화가
 * 전부이고, 그건 `@JsonCreator`/`@JsonProperty` 로 끝난다 — 모듈 하나를 더 들이는 것보다
 * 필드 이름을 눈에 보이게 적는 편이 계약 문서(`docs/API.md`)와 대조하기도 쉽다.
 */
data class StartSwapRequest @JsonCreator constructor(
    @param:JsonProperty("stationId") val stationId: String?,
    @param:JsonProperty("idToken") val idToken: IdTokenPayload?,
)

/**
 * `IdTokenType` 의 REST 표현 — `(idToken, type)`.
 *
 * **사용자 테이블의 FK 가 아니라 값 객체다**. 표준이 `type` 을 필수로 두므로
 * 계약도 둘을 함께 받는다. 로밍이 붙으면 우리 DB 에 없는 토큰이 여기로 들어온다.
 */
data class IdTokenPayload @JsonCreator constructor(
    @param:JsonProperty("idToken") val idToken: String?,
    @param:JsonProperty("type") val type: String?,
) {
    companion object {
        fun of(idToken: IdToken) = IdTokenPayload(idToken.idToken, idToken.type)
    }
}

/**
 * 교환 진행 상태.
 *
 * 도메인의 [SwapTransaction] sealed 타입을 그대로 직렬화하지 않고 여기로 옮긴다. 도메인
 * 타입의 모양이 곧 앱 계약이 되면 **상태머신을 고칠 수 없게 된다** — 필드 이름 하나를
 * 바꾸는 것이 클라이언트 파괴가 되기 때문이다. 경계에서 한 번 옮기는 값이 그 대가다.
 *
 * `IDLE` 이 없다. 그건 "아직 아무 일도 없다"이지 교환의 상태가 아니고, 조회 대상이 아니다.
 */
enum class SwapStatus {
    AUTHORIZED,
    HALF_IN,
    HALF_OUT,
    COMPLETED,
    OUT_TIMED_OUT,
}

/**
 * 배터리 한 개 — **`serialNumber`·`soC`·`soH` 를 전부 내보낸다**.
 *
 * > *"the price can depend, for example, on the difference between the state of charge of the
 * > old and new batteries"* (Part 2 S. Ch.1)
 *
 * 여기서 하나라도 버리면 나중에 과금이 **기존 데이터 위의 순수 계산**이 되지 못한다.
 * 요금 계산 자체는 범위 밖이지만, 그 근거를 없애는 것은 범위 조정이 아니라
 * 되돌릴 수 없는 전제다.
 */
data class BatteryView(
    val slotId: Int,
    val serialNumber: String,
    val soC: Double,
    val soH: Double,
) {
    companion object {
        fun of(battery: BatteryData) = BatteryView(
            slotId = battery.slotId.value,
            serialNumber = battery.serialNumber,
            soC = battery.soC,
            soH = battery.soH,
        )
    }
}

/**
 * ★ 장부 불균형 — `OUT_TIMED_OUT` 교환에서 **반드시 드러나야 하는 것** (실패 시나리오 F2).
 *
 * > **S03.FR.06** — *"Situation needs to be reported, because CSMS ends up with an
 * > **orphan BatteryIn for which a BatteryOut is missing**."*
 *
 * 조용히 "실패"로 뭉개지 않는다. 몇 개가 orphan 인지, **어떤 배터리인지** 보이지 않으면
 * 보상할 수 없다. [persisted] 는 그 채무가 프로세스 밖(H2)에도 남았는지다 — 인메모리 상태만
 * 있고 영속이 없다면 재시작으로 사라질 채무라는 뜻이므로, 그 차이를 숨기지 않는다.
 */
data class LedgerImbalanceView(
    val orphanCount: Int,
    val orphanBatteries: List<BatteryView>,
    val persisted: Boolean,
)

/**
 * 교환 1건의 조회 결과 — `GET /api/swaps/{id}`.
 *
 * @param startedAt 첫 배터리가 오간 시각. 아직 인가만 난 상태면 `null` 이다.
 * @param endedAt 종단 상태에 도달한 시각 (`COMPLETED` 의 완료, `OUT_TIMED_OUT` 의 타임아웃).
 * @param durationMillis [startedAt] ~ [endedAt]. 진행 중이면 `null` — 아직 답이 없는 값을
 *   0 으로 채우면 지표에서 0 초짜리 교환처럼 보인다.
 * @param ledgerImbalance `OUT_TIMED_OUT` 일 때만 값이 있다.
 */
data class SwapView(
    val id: String,
    val self: String,
    val stationId: String,
    val requestId: Int,
    val status: SwapStatus,
    val idToken: IdTokenPayload,
    val authorizedAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val durationMillis: Long?,
    val batteriesIn: List<BatteryView>,
    val batteriesOut: List<BatteryView>,
    val ledgerImbalance: LedgerImbalanceView?,
) {
    companion object {

        /**
         * 도메인 상태를 조회 결과로 옮긴다.
         *
         * [SwapTransaction.Idle] 은 교환이 아니므로 `null` 이다. 상태머신은 이상 사건을
         * 받아도 상태를 `Idle` 그대로 돌려주고 (F5), 그 사실이 보관소에 남는다 —
         * 그것을 "교환"으로 보여 주면 **열린 적 없는 교환이 조회되는** 셈이 된다.
         *
         * @param ledger 영속된 장부 불균형. `OUT_TIMED_OUT` 이 아니면 무시된다.
         */
        fun of(state: SwapTransaction, ledger: OutTimedOutRecord? = null): SwapView? = when (state) {
            is SwapTransaction.Idle -> null

            is SwapTransaction.Authorized -> base(
                key = state.key,
                status = SwapStatus.AUTHORIZED,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
            )

            is SwapTransaction.HalfIn -> base(
                key = state.key,
                status = SwapStatus.HALF_IN,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                startedAt = state.inAt,
                batteriesIn = state.batteriesIn,
            )

            is SwapTransaction.HalfOut -> base(
                key = state.key,
                status = SwapStatus.HALF_OUT,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                startedAt = state.outAt,
                batteriesOut = state.batteriesOut,
            )

            is SwapTransaction.Completed -> base(
                key = state.key,
                status = SwapStatus.COMPLETED,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                startedAt = state.startedAt,
                endedAt = state.completedAt,
                batteriesIn = state.batteriesIn,
                batteriesOut = state.batteriesOut,
            )

            is SwapTransaction.OutTimedOut -> base(
                key = state.key,
                status = SwapStatus.OUT_TIMED_OUT,
                idToken = state.idToken,
                authorizedAt = state.authorizedAt,
                startedAt = state.startedAt,
                endedAt = state.timedOutAt,
                batteriesIn = state.orphanBatteriesIn,
                imbalance = LedgerImbalanceView(
                    orphanCount = state.ledgerImbalance,
                    orphanBatteries = state.orphanBatteriesIn.map(BatteryView::of),
                    persisted = ledger != null,
                ),
            )
        }

        private fun base(
            key: SwapKey,
            status: SwapStatus,
            idToken: IdToken,
            authorizedAt: Instant,
            startedAt: Instant? = null,
            endedAt: Instant? = null,
            batteriesIn: List<BatteryData> = emptyList(),
            batteriesOut: List<BatteryData> = emptyList(),
            imbalance: LedgerImbalanceView? = null,
        ) = SwapView(
            id = SwapIds.of(key),
            self = SwapIds.pathOf(key),
            stationId = key.stationId.value,
            requestId = key.requestId.value,
            status = status,
            idToken = IdTokenPayload.of(idToken),
            authorizedAt = authorizedAt,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMillis = if (startedAt != null && endedAt != null) {
                Duration.between(startedAt, endedAt).toMillis()
            } else {
                null
            },
            batteriesIn = batteriesIn.map(BatteryView::of),
            batteriesOut = batteriesOut.map(BatteryView::of),
            ledgerImbalance = imbalance,
        )
    }
}

/** `POST /api/swaps` 한 번의 결말. HTTP 상태와 짝을 이룬다 — `docs/API.md` 표 참조. */
enum class StartSwapOutcome {

    /** 스테이션이 `Accepted`. 교환이 열렸다 → 201. */
    ACCEPTED,

    /**
     * 스테이션이 `Rejected`. **오류가 아니다** → 200.
     *
     * 재고 판정은 스테이션이 한다 (S02.FR.04). 배터리가 없는 것은 시스템 장애가
     * 아니라 정상적으로 일어나는 운영 상태이고, 앱은 그 사유를 이용자에게 보여 주면 된다.
     * 5xx 로 답하면 앱이 재시도 대상으로 오해한다.
     */
    REJECTED_BY_STATION,
}

/**
 * `POST /api/swaps` 응답.
 *
 * 두 결말이 한 타입인 이유는, 앱이 **`outcome` 하나만 보고 분기**할 수 있게 하기 위해서다.
 * 성공은 201, 거부는 200 이라 HTTP 상태로도 갈리지만 본문만으로도 판정 가능해야 한다.
 *
 * @param swap [StartSwapOutcome.ACCEPTED] 일 때만. 거부는 교환을 열지 않으므로 조회할
 *   대상 자체가 없다.
 * @param reasonCode 스테이션이 보낸 `statusInfo.reasonCode` **원문**. 부록
 *   표 밖의 값이 와도 버리지 않는다.
 * @param reason 사전 정의 사유로 해석됐으면 그 이름. 아니면 `null` 이고 [reasonCode] 만 남는다.
 */
data class StartSwapResponse(
    val outcome: StartSwapOutcome,
    val stationId: String,
    val requestId: Int,
    val swap: SwapView?,
    val reasonCode: String? = null,
    val reason: String? = null,
    val additionalInfo: String? = null,
    val rejectedAt: Instant? = null,
) {
    companion object {

        fun accepted(swap: SwapView) = StartSwapResponse(
            outcome = StartSwapOutcome.ACCEPTED,
            stationId = swap.stationId,
            requestId = swap.requestId,
            swap = swap,
        )

        fun rejected(rejection: SwapRejection) = StartSwapResponse(
            outcome = StartSwapOutcome.REJECTED_BY_STATION,
            stationId = rejection.key.stationId.value,
            requestId = rejection.key.requestId.value,
            swap = null,
            reasonCode = rejection.reasonCode,
            reason = rejection.reason?.name,
            additionalInfo = rejection.additionalInfo,
            rejectedAt = rejection.at,
        )
    }
}

/**
 * 오류 응답 본문.
 *
 * [error] 는 **기계가 분기할 값**이고 [message] 는 사람이 읽을 문장이다. 둘을 하나로 합치면
 * 클라이언트가 문자열 비교로 분기하게 되고, 문구를 고치는 것이 계약 파괴가 된다.
 */
data class ApiError(
    val error: String,
    val message: String,
    val stationId: String? = null,
    val idTokenStatus: String? = null,
)
