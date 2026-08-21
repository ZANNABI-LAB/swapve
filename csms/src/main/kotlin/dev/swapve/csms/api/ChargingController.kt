package dev.swapve.csms.api

import dev.swapve.csms.swap.ChargingTransactionRegistry
import dev.swapve.swap.StationId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 충전 트랜잭션 조회 (S04).
 *
 * ### 왜 교환 API 와 **다른 자원**인가
 *
 * 들어온 배터리의 충전은 **교환이 끝난 뒤에도 며칠 계속된다.** 그래서 충전은 교환의
 * 하위 자원이 될 수 없다 — `/api/swaps/{id}/charging` 같은 경로를 뚫으면 교환이 완료된
 * 순간부터 그 배터리의 충전을 가리킬 이름이 사라진다. 충전은 **스테이션의 슬롯**에 매인
 * 것이므로 경로도 스테이션 아래에 둔다.
 *
 * ### 왜 지금 만드나
 *
 * 범위는 **"지표 대시보드는 제외, REST 조회까지만"** 으로 그어져 있고,
 * §4.5 는 CSMS 가 충전 진행을 알고 싶을 때의 수단으로 `TransactionEvent(Updated)` +
 * measurand `SoC` 를 든다. 그 값이 기록으로만 있고 밖에서 읽을 길이 없으면, "어느 슬롯의
 * 어느 배터리가 얼마나 찼는가"를 확인하려면 시험 코드를 읽어야 한다. 조회 하나가 그 간극을
 * 메운다.
 *
 * **대시보드·UI 는 만들지 않는다** (결정 #2). 여기 있는 것은 JSON 두 개가 전부다.
 *
 * ### 쓰기가 없다
 *
 * 충전 트랜잭션은 스테이션이 만드는 사실이지 CSMS 가 지시하는 것이 아니다. 스마트차징도
 * 요금도 범위 밖이다 (확정된 범위 결정). 그래서 이 컨트롤러에는 `POST` 도 `PATCH` 도 없다.
 *
 * ### REST Basic 경계를 지난다
 *
 * 호출자 인증은 `ApiBasicAuthFilter` 에서 REST API 경로 공통으로 처리한다. OCPP 스테이션
 * 자격증명과는 별도 목록이다 (`docs/API.md` 첫머리).
 */
@RestController
@RequestMapping("/api/stations/{stationId}/charging-transactions")
class ChargingController(
    private val transactions: ChargingTransactionRegistry,
) {

    /**
     * 이 스테이션의 충전 트랜잭션 전부.
     *
     * 끝난 것도 함께 돌려준다. *"지금 충전 중인 것만"* 을 원하면 [ChargingTransactionView.status]
     * 로 거르면 되고, 끝난 트랜잭션을 목록에서 지우면 **재부팅으로 끊긴 트랜잭션**(S04.FR.11)
     * 같은 사실이 조회로는 영영 보이지 않게 된다.
     *
     * 없는 스테이션이어도 **빈 목록에 200** 이다. "그 스테이션에 도는 충전이 없다"와 "그런
     * 스테이션을 모른다"는 이 조회로 구분할 필요가 없고, 스테이션 자원은 아직 없다.
     */
    @GetMapping
    fun list(@PathVariable stationId: String): List<ChargingTransactionView> =
        transactions.of(StationId(stationId)).map(ChargingTransactionView::of)

    /** 충전 트랜잭션 1건. 없으면 404. */
    @GetMapping("/{transactionId}")
    fun find(
        @PathVariable stationId: String,
        @PathVariable transactionId: String,
    ): ResponseEntity<Any> {
        val transaction = transactions.find(StationId(stationId), transactionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError(
                    error = "NOT_FOUND",
                    message = "그런 충전 트랜잭션이 없다: $transactionId",
                    stationId = stationId,
                ),
            )

        return ResponseEntity.ok(ChargingTransactionView.of(transaction))
    }
}
