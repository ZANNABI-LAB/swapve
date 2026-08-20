package dev.swapve.csms.station

import dev.swapve.csms.ws.AuthMethod
import dev.swapve.swap.OperatorId
import dev.swapve.swap.StationId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `BootNotification` 이 알려 온 스테이션 등록 정보.
 *
 * [operatorId] 는 **값이 항상 하나여도 둔다**. 스테이션에 소유자 개념이 없으면
 * 나중에 로밍(OCPI)을 붙일 때 전 데이터 마이그레이션이 필요해진다. 지금은 설정에서 온
 * 상수 하나지만, 자리가 있다는 것이 요점이다.
 *
 * 스테이션이 보낸 필드는 **모르는 것까지 버리지 않고** 담는다. `serialNumber` 는
 * 나중에 배터리 이력 대조에, `firmwareVersion` 은 장애 상관분석에 쓰인다.
 *
 * @param authMethod 이 등록이 **어떻게 확인된 신원** 위에서 이뤄졌는지.
 *   지금은 늘 [AuthMethod.NONE] 이지만, 프로파일이 올라가면 같은 stationId 라도 확인 수준이
 *   달라진다. 그 차이를 기록하지 않으면 나중에 소급할 수 없다.
 */
data class StationRegistration(
    val stationId: StationId,
    val operatorId: OperatorId,
    val vendorName: String,
    val model: String,
    val serialNumber: String?,
    val firmwareVersion: String?,
    val bootReason: String,
    val authMethod: AuthMethod,
    val bootedAt: Instant,
)

/**
 * 부팅한 스테이션들 (인메모리).
 *
 * 영속은 하지 않는다 — 원문은 이미 `OcppEventLog` 에 남아 있고, 이 표는 거기서
 * 재구성 가능한 파생 상태다. 그게 이벤트 로그 이 요구하는 유일한 규칙이다.
 *
 * 스레드 안전하다.
 */
@Component
class StationRegistry {

    private val registrations = ConcurrentHashMap<StationId, StationRegistration>()

    /** 부팅을 기록한다. 재부팅이면 이전 등록을 덮고 그것을 돌려준다. */
    fun record(registration: StationRegistration): StationRegistration? =
        registrations.put(registration.stationId, registration)

    fun find(stationId: StationId): StationRegistration? = registrations[stationId]

    fun all(): List<StationRegistration> = registrations.values.sortedBy { it.stationId.value }
}
