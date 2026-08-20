package dev.swapve.csms.swap

import dev.swapve.csms.config.CsmsProperties
import dev.swapve.ocpp.swap.BatteryRejectionReason
import org.springframework.stereotype.Component

/**
 * 이 CPO 가 아는 배터리 일련번호 목록 (F3).
 *
 * ### 왜 필요한가
 *
 * 공유 모빌리티에서 "우리 배터리가 아닌 것이 들어왔다"는 실제로 일어나는 일이고, 그것을
 * 받아들이면 남의 자산이 우리 장부에 섞인다. 표준이 그 거부 방법을 정해 두었으므로
 * (확정 결정 결정 #7) 구현한다.
 *
 * ### 목록이 비어 있으면 검사하지 않는다
 *
 * 설정에 아무것도 없는 상태를 "모든 배터리가 미등록"으로 읽으면, 배터리 등록 절차를 아직
 * 만들지 않은 지금 **모든 교환이 거부된다.** 없는 정책을 있는 것처럼 굴게 만드는 것이라
 * 빈 목록은 *"우리는 아직 배터리를 식별하지 않는다"* 로 읽는다. 실제 등록 절차는
 * 나중에 볼 일이지 M7 의 범위가 아니다.
 *
 * 스레드 안전하다 — 불변 집합 하나다.
 */
@Component
class BatteryRegistry(properties: CsmsProperties) {

    /** 일련번호는 대소문자를 가리지 않는다 — RFID 계열 식별자의 통상 규약을 따른다. */
    private val known: Set<String> =
        properties.knownBatterySerials.map { it.lowercase() }.toSet()

    /** 배터리를 식별하는 정책이 켜져 있는가. 비어 있으면 [rejectionFor] 는 언제나 `null` 이다. */
    val enforcing: Boolean get() = known.isNotEmpty()

    fun isKnown(serialNumber: String): Boolean = !enforcing || serialNumber.lowercase() in known

    /**
     * 이 교환에 미등록 배터리가 있으면 거부 사유를, 없으면 `null` 을 돌려준다.
     *
     * 사유는 **하나만** 만든다. `statusInfo` 는 단일 값이고, 어느 배터리가 문제였는지는
     * [BatteryRejection.unknownSerials] 에 담아 [BatteryRejection.additionalInfo] 로 나간다.
     */
    fun rejectionFor(serialNumbers: List<String>): BatteryRejection? {
        if (!enforcing) return null
        val unknown = serialNumbers.filterNot { isKnown(it) }
        return if (unknown.isEmpty()) null else BatteryRejection(unknown)
    }
}

/**
 * 배터리 거부 사유.
 *
 * `BatterySwapResponse` 자체는 거부할 수 없으므로 이 값은 응답의 `customData` 로 나간다.
 * **응답의 성격은 여전히 수신 확인**이다 — CALLERROR 가 아니다.
 */
data class BatteryRejection(val unknownSerials: List<String>) {

    /** 부록 `reason_codes.csv` 의 사전 정의 값. */
    val reason: BatteryRejectionReason = BatteryRejectionReason.BATTERY_UNKNOWN

    /**
     * 사람이 읽을 설명. 스키마 상한이 1024자라 넉넉하지만, 배터리가 아주 많을 때를 대비해
     * 자른다 — 자르더라도 `BatterySwapRequest` 원문은 이벤트 로그에 그대로 남아 있다
     *.
     */
    val additionalInfo: String
        get() = "Not a battery of this CPO: ${unknownSerials.joinToString()}".take(MAX_ADDITIONAL_INFO)

    private companion object {
        /** `StatusInfoType.additionalInfo` 의 `maxLength` (스키마). */
        const val MAX_ADDITIONAL_INFO = 1024
    }
}
