package dev.swapve.ocpp.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 스테이션 단위 직렬화.
 *
 * ### 키가 `stationId` 다. 세션 객체가 아니다.
 *
 * 수평 확장을 막는 전제가 `synchronized(session)` 이다. 세션 객체로 락을
 * 잡으면 그 락은 **이 JVM 안에서만** 의미가 있고, 재접속으로 세션이 바뀌면 같은 스테이션인데
 * 다른 락이 된다. `stationId` 로 잡으면 지금은 로컬 락이고, 나중에 분산되면 **그대로
 * 파티셔닝 키**가 된다. 호출부는 한 줄도 바뀌지 않는다.
 *
 * ### 순서
 *
 * [Mutex] 는 공정하다 — 대기자는 도착 순서대로 깨어난다. 따라서 [withStation] 을 도착
 * 순서대로 호출하면 처리 순서도 도착 순서다. **호출자가 순서대로 부를 책임은 남는다** —
 * 이 클래스는 이미 뒤섞인 호출을 되돌려 놓지 못한다.
 *
 * 서로 다른 스테이션은 서로 다른 [Mutex] 를 쓰므로 한쪽이 느려도 다른 쪽을 막지 않는다.
 *
 * ### 수명
 *
 * 락은 스테이션당 하나씩 남고 회수하지 않는다. 스테이션 수는 유한하고(운영 규모는 수십 대),
 * 회수하려면 "지금 아무도 안 잡고 있다"를 원자적으로 확인해야 하는데 그 경합 비용이
 * 락 객체 하나보다 비싸다.
 */
class StationSerializer {

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /** [stationId] 의 차례가 올 때까지 기다렸다가 [block] 을 실행한다. */
    suspend fun <T> withStation(stationId: String, block: suspend () -> T): T =
        mutexOf(stationId).withLock { block() }

    /** 지금 그 스테이션의 차례를 누가 잡고 있는가. 테스트와 진단용이다. */
    fun isBusy(stationId: String): Boolean = mutexes[stationId]?.isLocked == true

    private fun mutexOf(stationId: String): Mutex = mutexes.computeIfAbsent(stationId) { Mutex() }
}
