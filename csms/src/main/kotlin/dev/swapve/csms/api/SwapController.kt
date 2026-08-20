package dev.swapve.csms.api

import dev.swapve.csms.swap.OutTimedOutLedger
import dev.swapve.csms.swap.RemoteSwapStart
import dev.swapve.csms.swap.RemoteSwapStarter
import dev.swapve.csms.swap.SwapTransactionRegistry
import dev.swapve.ocpp.session.OcppResult
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import dev.swapve.swap.SwapKey
import dev.swapve.swap.SwapTransaction
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * ★ **표준 S02 의 클라이언트 계약** — 앱이 소비하는 교환 API (PLAN §3, §6).
 *
 * ### 이 API 는 곁다리가 아니다
 *
 * 표준이 앱 시나리오를 이미 정의해 두었다:
 *
 * > **S02 - Battery Swap Remote Start**: *"EV Driver requests CSMS to initiate a battery swap
 * > **via a smartphone app, e.g. by scanning a QR code** or selecting the appropriate station
 * > in the app."*
 *
 * 즉 `앱 → CSMS → RequestBatterySwap → 스테이션` 이 **표준 유즈케이스**다. 앱 구현은 범위
 * 밖이지만 (PLAN §3 — *"구현 제외, 계약은 설계"*), 그 계약은 여기에 있다.
 *
 * ### 발신 로직을 다시 구현하지 않는다
 *
 * S02 발신은 **M7 에서 이미 끝났다** (PLAN §0 v3.1 범위 조정 — `TC_S_103_CSMS` 의 1단계가
 * CSMS 의 `RequestBatterySwapRequest` 발신이라 적합성보다 앞설 수 없었다). 이 컨트롤러가
 * 하는 일은 [RemoteSwapStarter.start] 를 **부르고 그 결과를 HTTP 로 옮기는 것**뿐이다.
 * 인가 판정(S02.FR.03)도, 스키마 자기검증도, `StationCommandBus` 발신도 전부 그 아래에 있다.
 *
 * ### 예외가 없는 이유
 *
 * [RemoteSwapStart] 가 **sealed 4종**이라 결말이 전부 값으로 온다. 연결이 없는 것도, 스테이션이
 * 거부한 것도, 인가가 나지 않은 것도 정상 운영 중 일어나는 일이지 예외적 사건이 아니다
 * (M4 [OcppResult] 와 같은 태도). 그래서 이 클래스에 `try/catch` 가 필요한 자리는 **요청
 * 본문 검증 하나뿐**이다.
 *
 * ### REST Basic 경계를 지난다
 *
 * 호출자 인증은 `ApiBasicAuthFilter` 에서 REST API 경로 공통으로 처리한다. OCPP 스테이션
 * 자격증명과는 별도 목록이다. 앱 권한 모델·토큰 회전·속도 제한은 아직 운영 범위가 아니다.
 */
@RestController
@RequestMapping("/api/swaps")
class SwapController(
    private val starter: RemoteSwapStarter,
    private val transactions: SwapTransactionRegistry,
    private val ledger: OutTimedOutLedger,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 교환을 시작한다 — `POST /api/swaps` (PLAN §3).
     *
     * | 결말 | HTTP | 왜 |
     * |---|---|---|
     * | [RemoteSwapStart.Accepted] | **201** + `Location` | 교환이 열렸다. 조회 URL 을 헤더와 본문 양쪽에 준다 |
     * | [RemoteSwapStart.Rejected] | **200** | 배터리가 없는 것은 **시스템 장애가 아니다** (F1 = `TC_S_102_CSMS`) |
     * | [RemoteSwapStart.NotAuthorized] | **403** | S02.FR.03 — 보내지 **않았다** |
     * | [RemoteSwapStart.Unreachable] | **502/503/504** | [unreachable] 참조 |
     *
     * ### `runBlocking` 을 쓴 이유
     *
     * [RemoteSwapStarter.start] 는 `suspend` 다 (M4 세션 계층이 응답을 기다리므로). 이
     * 컨트롤러는 서블릿 스택 위에 있고 **요청 스레드는 어차피 응답까지 붙잡혀 있다** —
     * 여기서 비동기 디스패치를 도입해도 얻는 것이 없고, 대신 이 API 를 읽는 사람이
     * 코루틴 스코프의 수명을 따져야 한다. 규모가 커져 스레드가 병목이 되면 그때 바꿀 자리는
     * 이 함수 하나다 (아래 계층은 이미 전부 `suspend` 다).
     */
    @PostMapping
    fun start(@RequestBody request: StartSwapRequest): ResponseEntity<Any> {
        val stationId = request.stationId?.takeIf { it.isNotBlank() }
            ?: return badRequest("stationId 는 비어 있을 수 없다")

        val idToken = try {
            IdToken(
                request.idToken?.idToken.orEmpty(),
                request.idToken?.type.orEmpty(),
            )
        } catch (e: IllegalArgumentException) {
            // 도메인 값 객체의 불변식이다 (PLAN §11.3 — (idToken, type) 값 객체). 여기서
            // 다시 검증하지 않고 그 판정을 그대로 400 으로 옮긴다.
            return badRequest(e.message ?: "idToken 이 올바르지 않다")
        }

        return when (val outcome = runBlocking { starter.start(StationId(stationId), idToken) }) {
            is RemoteSwapStart.Accepted -> accepted(outcome)
            is RemoteSwapStart.Rejected -> ResponseEntity.ok(StartSwapResponse.rejected(outcome.rejection))
            is RemoteSwapStart.NotAuthorized -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiError(
                    error = "NOT_AUTHORIZED",
                    message = "인가되지 않은 idToken 이라 RequestBatterySwap 을 보내지 않았다 (S02.FR.03)",
                    stationId = stationId,
                    idTokenStatus = outcome.status.wireValue,
                ),
            )

            is RemoteSwapStart.Unreachable -> unreachable(stationId, outcome.result)
        }
    }

    /**
     * 교환 진행 상태 — `GET /api/swaps/{id}` (PLAN §3).
     *
     * 없으면 **404** 다. 식별자의 모양이 틀린 경우도 같다 — [SwapIds.parse] KDoc 참조.
     *
     * 스테이션이 거부한 개시(F1)는 **여기서 조회되지 않는다.** 교환이 열리지 않았기 때문이다.
     * 그 사실은 `POST` 응답으로 이미 돌아갔고, 집계는 `GET /api/metrics/swaps` 에 있다.
     */
    @GetMapping("/{id}")
    fun find(@PathVariable id: String): ResponseEntity<Any> {
        val key = SwapIds.parse(id) ?: return notFound(id)
        val view = viewOf(key) ?: return notFound(id)
        return ResponseEntity.ok(view)
    }

    // ------------------------------------------------------------------ 내부

    private fun accepted(outcome: RemoteSwapStart.Accepted): ResponseEntity<Any> {
        // 교환은 보관소에 이미 들어가 있다. 그래도 반환된 상태가 아니라 **보관소에서 다시
        // 읽는** 이유는, 이 응답이 곧바로 이어질 GET 과 같은 값이어야 하기 때문이다.
        val view = viewOf(outcome.key) ?: SwapView.of(outcome.transaction)
        if (view == null) {
            // 상태머신이 Accepted 를 열지 못했다는 뜻이라 여기 올 수 없다. 조용히 200 을
            // 내는 대신 터뜨린다 — 이 값이 null 이면 앱은 조회할 대상이 없는 성공을 받는다.
            log.error("원격 개시가 Accepted 인데 교환이 열리지 않았다: key={}", outcome.key)
            return ResponseEntity.internalServerError().body(
                ApiError("SWAP_NOT_OPENED", "개시가 승인됐으나 교환이 열리지 않았다", outcome.key.stationId.value),
            )
        }

        return ResponseEntity.created(URI.create(view.self)).body(StartSwapResponse.accepted(view))
    }

    /**
     * 스테이션에 닿지 못했다 — [OcppResult] 를 HTTP 로 옮긴다.
     *
     * 하나로 뭉뚱그리지 않는 이유는 **앱이 해야 할 일이 서로 다르기** 때문이다. 연결이 없으면
     * 스테이션이 돌아올 때까지 기다려야 하고(503), 응답이 없으면 같은 교환이 실제로 열렸을
     * 수도 있으며(504), 프로토콜 오류는 재시도해도 같은 결과다(502).
     */
    private fun unreachable(stationId: String, result: OcppResult): ResponseEntity<Any> {
        val (status, message) = when (result) {
            is OcppResult.NotConnected ->
                HttpStatus.SERVICE_UNAVAILABLE to "스테이션이 연결돼 있지 않다"

            is OcppResult.TimedOut ->
                HttpStatus.GATEWAY_TIMEOUT to "스테이션이 정해진 시간 안에 응답하지 않았다"

            is OcppResult.Rejected ->
                HttpStatus.BAD_GATEWAY to "스테이션이 요청을 처리하지 못했다: ${result.errorCode}"

            is OcppResult.InvalidResponse ->
                HttpStatus.BAD_GATEWAY to "스테이션의 응답이 공식 스키마를 통과하지 못했다: ${result.errorDescription}"

            // 도달 불가 — Accepted 는 Unreachable 로 오지 않는다. 그래도 조용히 넘기지 않는다.
            is OcppResult.Accepted ->
                HttpStatus.INTERNAL_SERVER_ERROR to "예상치 못한 결과: $result"
        }

        return ResponseEntity.status(status).body(
            ApiError(error = status.name, message = message, stationId = stationId),
        )
    }

    /** 보관소의 상태를 조회 결과로. 열린 적 없는 교환([SwapTransaction.Idle])은 `null`. */
    private fun viewOf(key: SwapKey): SwapView? {
        val state = transactions.stateOf(key)
        if (state is SwapTransaction.Idle) return null

        // 영속 장부는 OUT_TIMED_OUT 일 때만 볼 필요가 있다. 매 조회마다 DB 를 때리지 않는다.
        val record = if (state is SwapTransaction.OutTimedOut) ledger.find(key) else null
        return SwapView.of(state, record)
    }

    private fun notFound(id: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError("NOT_FOUND", "그런 교환이 없다: $id"))

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ApiError("INVALID_REQUEST", message))
}
