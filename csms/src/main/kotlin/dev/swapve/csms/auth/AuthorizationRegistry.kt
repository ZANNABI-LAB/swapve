package dev.swapve.csms.auth

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.csms.ws.AuthMethod
import dev.swapve.csms.ws.StationPrincipal
import dev.swapve.swap.IdToken
import dev.swapve.swap.StationId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 인가 판정 결과 (`AuthorizationStatusEnumType`, `AuthorizeResponse` 스키마).
 *
 * [wireValue] 를 따로 두는 이유는 열거형 이름이 바뀌어도 전선 위의 값이 흔들리지 않게 하려는
 * 것이다. 스키마가 정본이고 코드가 그것을 따른다 (설계원칙 2).
 */
enum class AuthorizationStatus(val wireValue: String) {

    /** 인가됐다. S01 의 슬롯 개방으로 이어진다. */
    ACCEPTED("Accepted"),

    /**
     * 우리가 모르는 토큰이다.
     *
     * `Invalid` 가 아니라 `Unknown` 인 것이 의도다. `Invalid` 는 "이 토큰은 틀렸다"는 확정
     * 판정이지만, 우리가 아는 것은 **우리 목록에 없다**는 사실뿐이다. 로밍이 붙으면 같은
     * 토큰이 외부 조회로 인가될 수 있다 — 그때 뒤집힐 수 있는 판정을 확정형
     * 코드로 적어 두면 그게 잘못된 전제다.
     */
    UNKNOWN("Unknown"),
}

/**
 * 인가 시도 하나의 기록.
 *
 * **거부된 시도도 남긴다**. 로밍이 붙으면 이 목록이 곧 외부 조회 대상이 되고,
 * 지금 버리면 그때 아무것도 소급할 수 없다.
 */
data class AuthorizationAttempt(
    val stationId: StationId,
    val idToken: IdToken,
    val status: AuthorizationStatus,
    val authMethod: AuthMethod,
    val at: Instant,
)

/**
 * 인가가 난 사실. **교환 트랜잭션이 아니다.**
 *
 * @param at `AuthorizeResponse(Accepted)` 를 낸 시각. 훗날 `SwapEvent.Authorized.at` 이 된다.
 */
data class GrantedAuthorization(
    val stationId: StationId,
    val idToken: IdToken,
    val at: Instant,
)

/**
 * 인가 정책과 인가 사실의 보관소.
 *
 * ### ★ S01 에서 교환 트랜잭션을 열지 않는 이유 — 계획서의 오류 하나를 여기서 정정한다
 *
 * 상태머신은 S01 에서 `Authorize(Accepted)` 직후 `AUTHORIZED` 상태가 되며
 * **"requestId 확정"** 이라고 적고 있다. 그런데 같은 계획서 §4.4 표는 **S01 의 requestId 를
 * 스테이션이 발번한다**고 못박는다. 둘은 동시에 참일 수 없다 — CSMS 는 `Authorize` 를
 * 처리하는 시점에 requestId 를 알 방법이 없다. 그 값은 나중에 `BatterySwapRequest` 가 실어 온다.
 *
 * §4.4 표가 옳다. 스펙(Part 2 S01)이 `AuthorizeRequest` 에 requestId 를 두지 않기 때문이다.
 * 상태머신 그림의 "requestId 확정"은 S02(CSMS 개시)에서만 성립한다.
 *
 * M3 의 `SwapEvent.Authorized` 는 `SwapKey(stationId, requestId)` 를 요구하므로, 그대로
 * 흘려보낼 수 없다. 세 선택지 중 **(가)** 를 골랐다:
 *
 * > **인가를 idToken 기준으로 따로 들고 있다가, 첫 `BatterySwap` 이 requestId 를 실어 오면
 * > 그때 교환 트랜잭션을 연다.**
 *
 * (나)"인가는 전제조건으로만 기록한다"를 고르지 않은 이유는, 그러면 인가 시각이 교환
 * 트랜잭션에 남지 않아 `SwapTransaction.Authorized.authorizedAt` 이 실제 인가 시각이 아니라
 * 배터리 투입 시각이 되기 때문이다. 그건 과금 근거(교환 시작/종료 시각 보존)를
 * 조용히 훼손한다.
 *
 * ### swap-domain 을 고치지 않았다
 *
 * 고칠 필요가 없었다. `SwapTransaction.Idle` 은 `key` 가 `null` 이라 **교환 트랜잭션은
 * requestId 를 아는 순간까지 열리지 않아도 된다.** M6 에서 첫 `BatterySwap` 이 도착하면
 * [grantOf] 로 인가 사실을 꺼내
 * `SwapEvent.Authorized(SwapKey(stationId, requestId), grant.idToken, grant.at)` 를 만들어
 * 상태머신에 먼저 흘린 뒤 입고/출고 사건을 잇는다. 상태머신은 한 줄도 바뀌지 않고, M3 의
 * 기존 테스트도 그대로다.
 *
 * ### idToken 을 FK 로 묶지 않는다
 *
 * 인가 목록은 `(idToken, type)` 값 객체([IdToken])의 집합일 뿐 사용자 테이블이 아니다.
 * 로밍 토큰은 애초에 우리 DB 에 없다.
 *
 * 스레드 안전하다.
 */
@Component
class AuthorizationRegistry(properties: CsmsProperties) {

    /** 설정에서 온 인가 목록. MVP 의 정책은 이 집합 조회가 전부다. */
    private val allowed: Set<IdToken> =
        properties.authorizedIdTokens.map { IdToken(it.idToken, it.type) }.toSet()

    private val grants = ConcurrentHashMap<Pair<StationId, IdToken>, GrantedAuthorization>()

    private val attemptLock = Any()
    private val attempts = ArrayDeque<AuthorizationAttempt>()

    /**
     * 인가를 판정하고 **판정 결과와 무관하게 기록한다.**
     *
     * 인가가 나면 [GrantedAuthorization] 도 함께 남는다. 그것이 requestId 를 실어 올 첫
     * `BatterySwap` 을 기다리는 자리다 (위 KDoc).
     */
    fun authorize(principal: StationPrincipal, idToken: IdToken, at: Instant): AuthorizationAttempt =
        authorize(StationId(principal.stationId), idToken, principal.authMethod, at)

    /**
     * 신원 객체 없이 판정한다 — **S02 원격 개시가 쓴다.**
     *
     * S01 은 스테이션이 `AuthorizeRequest` 를 보내오므로 그 연결의 [StationPrincipal] 이
     * 손에 있다. S02 는 **CSMS 쪽에서 시작**하므로 그런 것이 없다. 그렇다고 신원 확인 수준을
     * 버리면 안 되므로, 호출자가 스테이션 등록에 남은 [authMethod] 를 찾아
     * 넘긴다. 없는 값을 지어내지 않는 대신 **모른다는 사실도 값**으로 받는다.
     */
    fun authorize(
        stationId: StationId,
        idToken: IdToken,
        authMethod: AuthMethod,
        at: Instant,
    ): AuthorizationAttempt {
        val status = if (idToken in allowed) AuthorizationStatus.ACCEPTED else AuthorizationStatus.UNKNOWN

        if (status == AuthorizationStatus.ACCEPTED) {
            grants[stationId to idToken] = GrantedAuthorization(stationId, idToken, at)
        }

        val attempt = AuthorizationAttempt(stationId, idToken, status, authMethod, at)
        record(attempt)
        return attempt
    }

    /** 이 스테이션에서 이 토큰에 인가가 난 적이 있으면 그 사실. M6 의 `BatterySwap` 이 꺼내 쓴다. */
    fun grantOf(stationId: StationId, idToken: IdToken): GrantedAuthorization? = grants[stationId to idToken]

    /** 인가 시도 기록을 오래된 것부터. */
    fun attempts(): List<AuthorizationAttempt> = synchronized(attemptLock) { attempts.toList() }

    /**
     * 기록을 [MAX_ATTEMPTS] 건으로 제한한다.
     *
     * 잘려도 정보가 사라지지 않는다 — `AuthorizeRequest` 원문은 이벤트 로그에 그대로 남아
     * 있고, 이 목록은 거기서 재구성 가능한 조회용 색인일 뿐이다.
     */
    private fun record(attempt: AuthorizationAttempt) = synchronized(attemptLock) {
        attempts.addLast(attempt)
        while (attempts.size > MAX_ATTEMPTS) attempts.removeFirst()
    }

    private companion object {
        const val MAX_ATTEMPTS = 1_000
    }
}
