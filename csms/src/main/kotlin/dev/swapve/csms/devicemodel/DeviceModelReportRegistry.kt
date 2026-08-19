package dev.swapve.csms.devicemodel

import dev.swapve.ocpp.swap.VariableRef
import dev.swapve.swap.StationId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 보고에 실려 온 변수 하나. `Actual` 속성의 값만 남는다 (`BatterySwapWire.ATTRIBUTE_ACTUAL`). */
data class ReportedVariable(
    val ref: VariableRef,
    val value: String?,
    val mutability: String? = null,
    val dataType: String? = null,
    val unit: String? = null,
)

/**
 * `NotifyReport` 조각들을 이어 붙인 **디바이스 모델 한 벌** (B03).
 *
 * @param parts 실제로 받은 조각 수. [missingSeqNos] 와 함께 읽어야 뜻이 온전하다 —
 *   3 건을 받았는데 `seqNo` 가 `0,1,3` 이면 조각은 3 건이고 유실은 1 건이다.
 * @param isComplete `tbc` 가 없는 마지막 조각까지 **빠짐없이** 왔다.
 * @param missingSeqNos 건너뛴 `seqNo`. 비어 있지 않으면 이 보고는 디바이스 모델의 전부가 아니다.
 */
data class DeviceModelReport(
    val stationId: StationId,
    val requestId: Int,
    val parts: Int,
    val variables: List<ReportedVariable>,
    val isComplete: Boolean,
    val missingSeqNos: List<Int>,
    val generatedAt: Instant?,
) {

    /** 유실도 중복도 없이 마지막 조각까지 왔다. */
    val isIntact: Boolean get() = isComplete && missingSeqNos.isEmpty()

    /**
     * 변수 하나의 값. **대소문자를 무시하고 찾는다** (PLAN §4.9 주의 2).
     *
     * 내보낼 때는 정본 철자, 받아서 맞춰 볼 때는 대소문자 무시가 규칙이다. 상대가
     * `maxsoc` 로 보고해도 [dev.swapve.ocpp.swap.DeviceModelVariables.maxSoc] 로 찾힌다.
     */
    fun valueOf(ref: VariableRef): String? = variables.firstOrNull { it.ref.identity == ref.identity }?.value

    fun contains(ref: VariableRef): Boolean = variables.any { it.ref.identity == ref.identity }
}

/**
 * `NotifyReport` 를 `requestId` 별로 **재조립한다** (B03, `TC_S_104_CS`).
 *
 * ### ★ 어긋난 `seqNo` 를 조용히 삼키지 않는다
 *
 * 이 프로젝트는 **유실 메시지를 감사 항목으로 센다** (PLAN §5.3 — *"유실 메시지 0"*).
 * 조각이 빠진 것을 눈감고 이어 붙이면, 그 결과는 "디바이스 모델의 일부"가 아니라
 * **일부인 줄 모르는 전부**가 된다. 그런 값으로 판단하느니 불완전하다는 사실이 값에
 * 실려 있는 편이 낫다 — 그래서 빠진 번호가 [DeviceModelReport.missingSeqNos] 로 남고,
 * [DeviceModelReport.isIntact] 가 거짓이 된다.
 *
 * 빠진 조각을 **재요청하지도 않는다.** 표준에 부분 재전송 수단이 없다 (`GetBaseReport` 는
 * 언제나 처음부터다). 다시 청하는 판단은 이 값을 읽는 쪽의 몫이고, 여기는 사실만 남긴다.
 *
 * ### 인메모리로 끝낸다
 *
 * 조회는 REST 까지가 범위다 (PLAN §10 결정 #2). 디바이스 모델은 스테이션이 소유하고
 * (PLAN §4.5), 여기 남는 것은 **그때 그 스테이션이 이렇게 답했다**는 사본일 뿐이라
 * 재시작 후까지 살아남아야 할 이유가 없다 — 오히려 낡은 사본이 남는 쪽이 위험하다.
 */
@Component
class DeviceModelReportRegistry {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Key(val stationId: String, val requestId: Int)

    private class Assembly {
        val variables = mutableListOf<ReportedVariable>()
        val missing = mutableListOf<Int>()
        var parts = 0
        var expectedSeqNo = 0
        var complete = false
        var generatedAt: Instant? = null
    }

    private val reports = ConcurrentHashMap<Key, Assembly>()

    /**
     * 조각 하나를 붙인다.
     *
     * @param seqNo 이 조각의 번호. 0 부터 1 씩 올라야 한다.
     * @param tbc 뒤에 더 온다. 거짓이면 이 조각이 마지막이다.
     */
    fun record(
        stationId: StationId,
        requestId: Int,
        seqNo: Int,
        tbc: Boolean,
        generatedAt: Instant?,
        variables: List<ReportedVariable>,
    ) {
        val assembly = reports.computeIfAbsent(Key(stationId.value, requestId)) { Assembly() }
        synchronized(assembly) {
            if (seqNo != assembly.expectedSeqNo) {
                // 건너뛴 번호를 전부 적는다. 뒤로 간 번호(재전송·중복)는 빠뜨릴 것이 없으므로
                // 범위가 비고, 대신 아래 경고가 그 사실을 남긴다.
                val skipped = (assembly.expectedSeqNo until seqNo).toList()
                assembly.missing += skipped
                log.warn(
                    "NotifyReport 의 seqNo 가 어긋났다: station={} requestId={} 기대={} 받음={} 유실={}",
                    stationId, requestId, assembly.expectedSeqNo, seqNo, skipped,
                )
            }

            assembly.parts++
            assembly.expectedSeqNo = maxOf(assembly.expectedSeqNo, seqNo + 1)
            assembly.variables += variables
            generatedAt?.let { assembly.generatedAt = it }
            if (!tbc) assembly.complete = true
        }
    }

    /** 재조립 결과. 그 스테이션의 그 요청에 대한 조각이 하나도 없으면 `null`. */
    fun find(stationId: StationId, requestId: Int): DeviceModelReport? {
        val assembly = reports[Key(stationId.value, requestId)] ?: return null
        return synchronized(assembly) {
            DeviceModelReport(
                stationId = stationId,
                requestId = requestId,
                parts = assembly.parts,
                variables = assembly.variables.toList(),
                isComplete = assembly.complete,
                missingSeqNos = assembly.missing.toList(),
                generatedAt = assembly.generatedAt,
            )
        }
    }
}
