package dev.swapve.ocpp.session

import java.time.Instant

/** 메시지가 오간 방향. */
enum class MessageDirection {
    /** 스테이션 → 우리 */
    INBOUND,

    /** 우리 → 스테이션 */
    OUTBOUND,
}

/**
 * 오간 OCPP 메시지 하나의 추가 전용(append-only) 기록.
 *
 * 필드 구성은 이벤트 로그 설계 그대로다. **파생 상태가 이 로그에서 재구성 가능해야 한다는 것이
 * 유일한 규칙**이므로, 해석한 결과가 아니라 [payload] 에 **원문 그대로**를 남긴다.
 * 우리가 지금 이해하지 못하는 필드도 나중에 읽을 수 있어야 한다 (정보를 버리지 않는다).
 *
 * 이 로그 하나가 과금·로밍·감사·수평확장 네 가지 확장을 동시에 연다 (표).
 *
 * @param seq **스테이션 내** 증가 순번. 스테이션끼리는 비교 대상이 아니다 — 서로 다른 연결의
 * 메시지에 전역 순서를 매기는 것은 지킬 수 없는 약속이다 (수평 확장).
 * @param action `BatterySwap`, `TransactionEvent` … CALLRESULT/CALLERROR 는 원래 CALL 의
 *   action 을 채운다. 짝을 찾지 못했으면 `null` 이다 — 모른다는 사실도 기록이다.
 * @param messageId OCPP-J uniqueId. 메시지 타입조차 읽지 못한 경우에는
 *   [dev.swapve.ocpp.rpc.OcppFrameCodec.UNREADABLE_MESSAGE_ID] 가 들어온다.
 * @param payload 프레임 **원문 한 줄 전체**. 페이로드만이 아니라 `[2,"id","Action",{...}]` 전부다.
 */
data class OcppEventRecord(
    val seq: Long,
    val stationId: String,
    val direction: MessageDirection,
    val action: String?,
    val messageId: String,
    val payload: String,
    val occurredAt: Instant,
)

/**
 * 이벤트를 받아 적는 자리.
 *
 * 이 프로젝트는 구현체 하나짜리 인터페이스를 두지 않는다. 그런데 이벤트 로그는 **인터셉터 자리 하나**를
 * 명시적으로 요구한다. 지금은 인메모리 구현 하나뿐이고 DB 영속은 저장소 결정(설계 원칙) 뒤로 미룬다 —
 * 그 교체 지점이 여기다.
 *
 * `seq` 부여는 구현의 몫이다. 스테이션 내 순서를 아는 것은 로그를 쥔 쪽이지 세션이 아니다.
 */
fun interface OcppEventSink {

    /** 기록하고, 순번이 부여된 레코드를 돌려준다. */
    fun append(
        stationId: String,
        direction: MessageDirection,
        action: String?,
        messageId: String,
        payload: String,
        occurredAt: Instant,
    ): OcppEventRecord
}

/**
 * 인메모리 이벤트 로그.
 *
 * **DB 영속은 이 마일스톤에서 하지 않는다** (저장소 결정, 이벤트 로그 "테이블 1개 + 인터셉터 1개").
 * 지금 필요한 것은 "파생 상태를 이 로그만으로 재구성할 수 있는가"를 실제로 증명하는 것이고,
 * 그건 저장 매체와 무관하다.
 *
 * 스레드 안전하다. `seq` 부여와 적재가 한 임계구역 안에서 일어나므로 순번과 적재 순서가
 * 어긋나지 않는다.
 */
class InMemoryOcppEventLog : OcppEventSink {

    private val lock = Any()
    private val byStation = LinkedHashMap<String, MutableList<OcppEventRecord>>()
    private val arrivalOrder = ArrayList<OcppEventRecord>()

    override fun append(
        stationId: String,
        direction: MessageDirection,
        action: String?,
        messageId: String,
        payload: String,
        occurredAt: Instant,
    ): OcppEventRecord = synchronized(lock) {
        val records = byStation.getOrPut(stationId) { ArrayList() }
        val record = OcppEventRecord(
            seq = records.size + 1L,
            stationId = stationId,
            direction = direction,
            action = action,
            messageId = messageId,
            payload = payload,
            occurredAt = occurredAt,
        )
        records += record
        arrivalOrder += record
        record
    }

    /** 한 스테이션의 기록을 `seq` 순서로. 재구성은 항상 이 단위로 한다. */
    fun of(stationId: String): List<OcppEventRecord> = synchronized(lock) {
        byStation[stationId].orEmpty().toList()
    }

    /** 전체 기록을 적재 순서로. 스테이션을 가로지르는 `seq` 는 없으므로 순서는 적재순일 뿐이다. */
    fun all(): List<OcppEventRecord> = synchronized(lock) { arrayListOf<OcppEventRecord>().apply { addAll(arrivalOrder) } }

    fun size(): Int = synchronized(lock) { arrivalOrder.size }
}
