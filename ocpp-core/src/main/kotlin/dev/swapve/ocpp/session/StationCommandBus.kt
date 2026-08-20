package dev.swapve.ocpp.session

import java.util.concurrent.ConcurrentHashMap

/**
 * 스테이션에 명령을 보내는 유일한 통로.
 *
 * ### 왜 이것만 인터페이스인가
 *
 * 이 프로젝트는 구현체 하나짜리 인터페이스를 두지 않는다. 그런데 수평 확장 여지는 이것을 명시적으로
 * 요구한다. 모순이 아니다 — **이건 미래를 위한 추상화가 아니라 "세션 객체를 상위 계층에
 * 흘리지 않는다"는 지금의 위생 규칙**이다. 상위 계층이 `session.send(...)` 를 쓰기 시작하면
 * "세션이 이 JVM 에 있다"는 전제가 코드 전체에 퍼지고, 그 전제는 되돌리기 어렵다.
 *
 * 상위 계층은 언제나 `stationId` 로 지시한다. 그게 로컬 락 키이자 훗날의 파티셔닝 키다.
 */
interface StationCommandBus {

    /**
     * [stationId] 에 CALL 을 보내고 결과를 돌려준다.
     *
     * **예외를 던지지 않는다.** 연결이 없는 것도 결과다 ([OcppResult.NotConnected]).
     */
    suspend fun send(stationId: String, call: OcppCall): OcppResult
}

/**
 * 연결된 세션 목록.
 *
 * [sessionOf] 가 `internal` 인 것이 핵심이다 — **세션 객체는 이 모듈 밖으로 나가지 않는다**
 *. 상위 계층이 볼 수 있는 것은 [StationCommandBus] 와 [isConnected] 뿐이다.
 *
 * 스레드 안전하다.
 */
class SessionRegistry {

    private val sessions = ConcurrentHashMap<String, OcppSession>()

    /**
     * 연결이 맺어졌다. 같은 스테이션의 이전 세션이 있으면 대체하고 그것을 돌려준다.
     *
     * 대체된 세션을 닫는 것은 호출자 몫이다 — 이 클래스는 전송을 모른다.
     */
    fun register(session: OcppSession): OcppSession? = sessions.put(session.stationId, session)

    /**
     * 연결이 끊겼다. **그 세션이 아직 등록된 것일 때만** 지운다.
     *
     * 재접속이 빠르면 새 세션이 이미 등록된 뒤에 옛 연결의 종료 처리가 도착한다. 무조건
     * 지우면 살아 있는 새 세션이 사라진다.
     */
    fun unregister(session: OcppSession): Boolean = sessions.remove(session.stationId, session)

    fun isConnected(stationId: String): Boolean = sessions.containsKey(stationId)

    val connectedStationIds: Set<String> get() = sessions.keys.toSet()

    internal fun sessionOf(stationId: String): OcppSession? = sessions[stationId]
}

/**
 * MVP 구현 — 로컬 세션 레지스트리를 조회해 발신한다. 그게 전부다.
 *
 * Redis 도 메시지 버스도 클러스터 멤버십도 없다 ("지금 하지 않을 것").
 * 분산이 필요해지면 **이 클래스만 바뀐다.** 호출부는 한 줄도 안 바뀐다.
 */
class LocalStationCommandBus(private val registry: SessionRegistry) : StationCommandBus {

    override suspend fun send(stationId: String, call: OcppCall): OcppResult =
        registry.sessionOf(stationId)?.call(call) ?: OcppResult.NotConnected(stationId)
}
