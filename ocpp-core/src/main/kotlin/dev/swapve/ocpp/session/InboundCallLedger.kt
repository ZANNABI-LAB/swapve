package dev.swapve.ocpp.session

/**
 * 수신한 CALL 하나를 가리키는 키.
 *
 * **연결이 아니라 스테이션으로 잡는다.** messageId 는 "같은 스테이션 식별자에 대해 그 발신자가
 * 이전에 쓴 모든 값과 달라야 하고, 연결이 새로 맺어져도 마찬가지"이기 때문이다 (Part 4 §4.1.4).
 * 연결을 키에 넣는 순간 재접속 재전송을 새 메시지로 오인하게 된다 — 그게 F6 이다.
 */
data class InboundCallKey(
    val stationId: String,
    val messageId: String,
)

/** [InboundCallLedger.claim] 의 판정. */
sealed interface CallClaim {

    /** 처음 보는 메시지다. 처리해도 된다. */
    data object Fresh : CallClaim

    /**
     * 이미 처리를 끝낸 메시지다. **상위 계층을 다시 부르지 말고** [responseText] 를 그대로
     * 다시 보낸다. 부수효과는 한 번만 일어난다 (F4·F6).
     */
    data class AlreadyAnswered(val responseText: String) : CallClaim

    /**
     * 아직 처리 중인 메시지다. CALLERROR 로 답한다 — *"already processing a message with
     * this unique identifier"* 는 스펙이 명시한 CALLERROR 사유다 (Part 4 §4.2.3).
     *
     * 기다렸다가 같은 답을 주는 선택지도 있지만 그러지 않는다. 상대가 재전송했다는 것은
     * 상대 쪽에서 이미 타임아웃이 났다는 뜻이고, 그 연결을 더 붙잡고 있을 이유가 없다.
     */
    data object InFlight : CallClaim
}

/**
 * 수신한 CALL 의 멱등 원장 (F4·F6).
 *
 * **세션보다 오래 산다.** 세션은 연결 하나의 수명이지만 원장은 스테이션의 수명을 따라간다.
 * WebSocket 이 끊겼다 다시 붙고 스테이션이 `BatterySwap` CALL 을 재전송하는 상황이 정확히
 * 이것이며, 여기서 멱등이 깨지면 배터리 장부가 중복 계상된다 (M4·M7 의 핵심 위험).
 *
 * ### 상한
 *
 * 기억은 무한히 자랄 수 없다. [maxEntries] 개를 넘으면 **가장 오래 손대지 않은 것부터**
 * 버린다(LRU).
 *
 * 기본값 [DEFAULT_MAX_ENTRIES] = 10,000 의 근거:
 * - 부하 목표는 스테이션 20 대다 (S4). 교환 1 건이 오가는 OCPP 메시지는
 *   `TC_S_103_CSMS` 기준 20 여 개(적합성)이므로, 20 대 × 25 건 × 20 메시지 ≈ 10,000 이
 *   **전 스테이션이 동시에 25 건씩 교환하는 동안의 전량**에 해당한다.
 * - 재전송은 상대가 타임아웃을 낸 직후에만 일어난다 (Part 4 §4.1.1). 그 창을 한참 지나
 *   밀려난 항목은 애초에 재전송 대상이 아니다.
 * - 항목 하나는 키와 응답 원문 뿐이라 10,000 개라도 수 MB 안쪽이다.
 *
 * 스레드 안전하다.
 */
class InboundCallLedger(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "멱등 원장의 상한은 1 이상이어야 한다: $maxEntries" }
    }

    private sealed interface Entry {
        data object InProgress : Entry
        data class Answered(val responseText: String) : Entry
    }

    private val lock = Any()

    // accessOrder = true — 조회도 "최근 쓰임"으로 친다. 재전송이 반복되는 메시지가
    // 새 메시지에 밀려 사라지면 멱등이 깨지므로, 삽입순이 아니라 사용순으로 버려야 한다.
    private val entries = object : LinkedHashMap<InboundCallKey, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InboundCallKey, Entry>): Boolean =
            size > maxEntries
    }

    /**
     * 이 메시지를 지금 처리해도 되는지 판정하고, 처음이면 "처리 중"으로 표시한다.
     *
     * 판정과 표시가 한 임계구역 안에서 일어난다. 나뉘어 있으면 같은 messageId 두 개가
     * 나란히 [CallClaim.Fresh] 를 받아 상위 계층이 두 번 불린다.
     */
    fun claim(key: InboundCallKey): CallClaim = synchronized(lock) {
        when (val existing = entries[key]) {
            null -> {
                entries[key] = Entry.InProgress
                CallClaim.Fresh
            }

            is Entry.InProgress -> CallClaim.InFlight
            is Entry.Answered -> CallClaim.AlreadyAnswered(existing.responseText)
        }
    }

    /**
     * 처리를 끝내고 회신한 프레임 **원문**을 기억한다.
     *
     * 페이로드가 아니라 인코딩된 한 줄을 통째로 남기는 이유: 재전송할 때 다시 만들지 않고
     * 그대로 내보내면 **바이트 단위로 같은 응답**이 보장된다. 다시 만들면 그 사이 코드가
     * 바뀌었을 때 두 번째 응답이 첫 번째와 달라질 수 있다.
     */
    fun complete(key: InboundCallKey, responseText: String) = synchronized(lock) {
        entries[key] = Entry.Answered(responseText)
    }

    /**
     * 처리 흔적을 지운다. 상위 계층이 예외로 끝났을 때 쓴다.
     *
     * 지우지 않으면 그 messageId 는 영원히 [CallClaim.InFlight] 로 남아 재시도가 막힌다.
     * 처리에 실패했다면 재전송으로 다시 시도할 수 있어야 한다.
     */
    fun release(key: InboundCallKey) = synchronized(lock) {
        entries.remove(key)
        Unit
    }

    /** 기억하고 있는 메시지 수. 상한이 실제로 도는지 확인하는 용도다. */
    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        /** 근거는 클래스 KDoc 의 "상한" 절에 있다. */
        const val DEFAULT_MAX_ENTRIES = 10_000

        private const val INITIAL_CAPACITY = 256
        private const val LOAD_FACTOR = 0.75f
    }
}
