package dev.swapve.station

import dev.swapve.swap.IdToken
import kotlinx.coroutines.runBlocking

/**
 * 실행 진입점 — 붙어서 교환 1건을 완주하고 끝난다.
 *
 * ```
 * ./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In"
 * ```
 *
 * 인자는 전부 선택이고 기본값은 `TC_S_103_CSMS` 의 모양을 따른다 — **배터리 2개 세트**,
 * 입고 슬롯과 출고 슬롯이 서로 다르다 (PLAN §7.1).
 */
object StationSimCli {

    private val USAGE = """
        station-sim — OCPP 2.1 배터리 교환 스테이션 시뮬레이터

          --csms-url <url>          CSMS 엔드포인트 (스테이션 식별자 제외). 기본 ws://localhost:8080/ocpp
          --station-id <id>         스테이션 식별자. 기본 CS001
          --username <value>        Basic 인증 사용자명. 기본 station-id
          --password <value>        Basic 인증 비밀번호. 생략하면 Authorization 헤더를 보내지 않는다
          --slots <n>               슬롯(=EVSE) 수. 기본 4
          --set-size <n>            한 번에 오가는 배터리 수. 기본 2
          --swap-order <In-Out|Out-In>  교환 순서. 기본 In-Out
          --id-token <value>        교환 인가 토큰. 기본 RFID-0001
          --id-token-type <type>    토큰 종류. 기본 ISO14443
          --charging-id-token <v>   충전 트랜잭션용 대체 토큰(BatterySwapCtrlr.IdToken).
                                    생략하면 인가 없는 트랜잭션으로 보고한다
          --request-id <n>          교환 상관 번호. 기본 1001.
                                    --remote-start 면 CSMS 가 보낸 값으로 덮인다 (S02.FR.02)
          --remote-start            ★ S02 — 스스로 시작하지 않고 CSMS 의
                                    RequestBatterySwapRequest 를 기다린다.
                                    앱이 교환을 거는 표준 유즈케이스다:
                                      POST /api/swaps {"stationId": ..., "idToken": ...}
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            println(USAGE)
            return
        }

        val remoteStart = args.any { it == REMOTE_START_FLAG }
        val options = parse(args.filterNot { it == REMOTE_START_FLAG }.toTypedArray())
        val config = buildConfig(options)

        println("station-sim → ${config.connectUrl} (순서 ${config.swapOrder.wireValue}, 배터리 ${config.insertSlots.size} 개)")

        StationSimulator(config).use { simulator ->
            var requestId = config.requestId
            runBlocking {
                simulator.connect()
                if (remoteStart) {
                    // S02 — 개시 주체가 CSMS(앱)다. 인가도 CSMS 가 이미 했으므로 여기서
                    // Authorize 를 보내지 않는다 (PLAN §4.4).
                    simulator.boot()
                    println("S02 대기 중 — CSMS 의 RequestBatterySwap 을 기다린다.")
                    println("  curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \\")
                    println("       -d '{\"stationId\":\"${config.stationId}\",\"idToken\":" +
                        "{\"idToken\":\"${config.idToken.idToken}\",\"type\":\"${config.idToken.type}\"}}'")
                    // 상관 번호는 CSMS 가 발번한 값을 승계한다 (S02.FR.02).
                    requestId = simulator.awaitRemoteStart()
                    simulator.runRemoteSwap()
                } else {
                    simulator.bootAndSwap()
                }
            }
            println("교환 완주: requestId=$requestId, 오간 메시지 ${simulator.eventLog.size()} 건")
        }
    }

    /** S02 원격 개시 대기 모드. 값을 받지 않는 유일한 인자라 [parse] 에 넣지 않는다. */
    private const val REMOTE_START_FLAG = "--remote-start"

    /**
     * 기본 시나리오 구성.
     *
     * 앞의 `setSize` 개 슬롯은 비어 있고(투입 대상), 그다음 `setSize` 개 슬롯에는 내줄
     * 배터리가 들어 있다. 나머지 슬롯은 비워 둔다. **입고 슬롯과 출고 슬롯이 다르다**는
     * `TC_S_103_CSMS` 의 전제를 그대로 옮긴 것이다 (PLAN §7.1).
     */
    private fun buildConfig(options: Map<String, String>): StationSimConfig {
        val slotCount = options.int("slots", 4)
        val setSize = options.int("set-size", 2)
        require(slotCount >= setSize * 2) {
            "슬롯이 부족하다: 투입 $setSize 개 + 반출 $setSize 개 = ${setSize * 2} 개가 필요한데 $slotCount 개다"
        }

        val insertSlots = (1..setSize).toList()
        val dispenseSlots = (setSize + 1..setSize * 2).toList()

        val slots = (1..slotCount).map { slotId ->
            SlotConfig(
                slotId = slotId,
                battery = if (slotId in dispenseSlots) {
                    // 내주는 배터리는 잘 충전돼 있다 (S04.FR.05 TargetSoC 를 넘긴 상태).
                    SimBattery("BAT-FULL-$slotId", soC = 95.0, soH = 98.0)
                } else {
                    null
                },
            )
        }

        return StationSimConfig(
            csmsUrl = options["csms-url"] ?: "ws://localhost:8080/ocpp",
            stationId = options["station-id"] ?: "CS001",
            username = options["username"],
            password = options["password"],
            slots = slots,
            idToken = IdToken(
                options["id-token"] ?: "RFID-0001",
                options["id-token-type"] ?: "ISO14443",
            ),
            requestId = options.int("request-id", 1001),
            insertSlots = insertSlots,
            dispenseSlots = dispenseSlots,
            // 이용자가 가져온 헌 배터리들 — 다 쓴 상태다.
            incomingBatteries = insertSlots.mapIndexed { index, slotId ->
                SimBattery("BAT-USED-$slotId", soC = 12.0 + index, soH = 90.0 - index)
            },
            swapOrder = swapOrderOf(options["swap-order"]),
            chargingIdToken = options["charging-id-token"],
        )
    }

    private fun swapOrderOf(value: String?): SwapOrder {
        if (value == null) return SwapOrder.IN_OUT
        return SwapOrder.entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) || it.name == value }
            ?: error("모르는 교환 순서: $value (In-Out 또는 Out-In)")
    }

    /** `--key value` 짝만 읽는다. 외부 파서 라이브러리를 쓰지 않는다 — 의존성 0 이 원칙이다. */
    private fun parse(args: Array<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            require(token.startsWith("--")) { "모르는 인자: $token\n\n$USAGE" }
            require(index + 1 < args.size) { "값이 없는 인자: $token\n\n$USAGE" }
            options[token.removePrefix("--")] = args[index + 1]
            index += 2
        }
        return options
    }

    private fun Map<String, String>.int(key: String, fallback: Int): Int =
        this[key]?.let { it.toIntOrNull() ?: error("숫자가 아닌 값: --$key $it") } ?: fallback
}
