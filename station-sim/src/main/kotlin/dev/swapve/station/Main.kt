package dev.swapve.station

import dev.swapve.ocpp.session.MessageDirection
import dev.swapve.ocpp.swap.BatterySwapWire
import dev.swapve.swap.IdToken
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 실행 진입점 — 붙어서 교환 1건을 완주하고 끝난다.
 *
 * ```
 * ./gradlew :station-sim:run --args="--csms-url ws://localhost:8080/ocpp --station-id CS001 --swap-order Out-In"
 * ```
 *
 * 인자는 전부 선택이고 기본값은 `TC_S_103_CSMS` 의 모양을 따른다 — **배터리 2개 세트**,
 * 입고 슬롯과 출고 슬롯이 서로 다르다.
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
          --fault-f6                ★ F6 — 출고 직전에 연결이 끊긴 상황을 재현한다.
                                    입고까지 진행한 뒤 끊고, 재접속해 **같은 messageId 로**
                                    BatteryIn 을 재전송한다. 상대 CSMS 가 멱등 원장을
                                    가졌다면 저장된 응답을 다시 낼 뿐 장부는 늘지 않는다.
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            println(USAGE)
            return
        }

        val remoteStart = args.any { it == REMOTE_START_FLAG }
        val faultF6 = args.any { it == FAULT_F6_FLAG }
        require(!(remoteStart && faultF6)) {
            "$REMOTE_START_FLAG 과 $FAULT_F6_FLAG 를 함께 쓸 수 없다 — F6 은 스테이션이 개시하는 경로다"
        }
        val options = parse(args.filterNot { it == REMOTE_START_FLAG || it == FAULT_F6_FLAG }.toTypedArray())
        val config = buildConfig(options)

        println("station-sim → ${config.connectUrl} (순서 ${config.swapOrder.wireValue}, 배터리 ${config.insertSlots.size} 개)")

        val faults = if (faultF6) {
            FaultInjection.failingAt(SimStep.BATTERY_OUT, "교환 도중 연결이 끊겼다")
        } else {
            FaultInjection.None
        }

        // 상대와의 사이에서 난 실패는 스택트레이스가 답이 아니다. 무응답도 끊긴 연결도
        // 이 도구가 알아내려고 온 결과이지 버그가 아니라서, 한 줄로 말하고 1 로 끝낸다.
        // 시나리오 판정([runF6] 의 check)이나 인자 오류는 여기서 잡지 않는다 — 그건 우리
        // 쪽이 틀렸다는 뜻이라 트레이스가 그대로 필요하다.
        try {
            runScenario(config, faults, remoteStart, faultF6)
        } catch (fault: SimulatedFault) {
            System.err.println("station-sim 중단: ${fault.message}")
            exitProcess(1)
        } catch (failed: CallFailed) {
            System.err.println("station-sim 중단: ${failed.message}")
            exitProcess(1)
        }
    }

    /** 세 경로(S02 대기 · F6 · 기본 교환) 중 하나를 골라 끝까지 돌린다. [main] 이 그 실패만 받는다. */
    private fun runScenario(
        config: StationSimConfig,
        faults: FaultInjection,
        remoteStart: Boolean,
        faultF6: Boolean,
    ) {
        StationSimulator(config, faults = faults).use { simulator ->
            var requestId = config.requestId
            runBlocking {
                simulator.connect()
                if (remoteStart) {
                    // S02 — 개시 주체가 CSMS(앱)다. 인가도 CSMS 가 이미 했으므로 여기서
                    // Authorize 를 보내지 않는다.
                    simulator.boot()
                    println("S02 대기 중 — CSMS 의 RequestBatterySwap 을 기다린다.")
                    println("  curl -X POST localhost:8080/api/swaps -H 'Content-Type: application/json' \\")
                    println("       -d '{\"stationId\":\"${config.stationId}\",\"idToken\":" +
                        "{\"idToken\":\"${config.idToken.idToken}\",\"type\":\"${config.idToken.type}\"}}'")
                    // 상관 번호는 CSMS 가 발번한 값을 승계한다 (S02.FR.02).
                    requestId = simulator.awaitRemoteStart()
                    simulator.runRemoteSwap()
                } else if (faultF6) {
                    runF6(simulator, config)
                } else {
                    simulator.bootAndSwap()
                }
            }
            if (faultF6) {
                println("F6 완주: requestId=$requestId, 오간 메시지 ${simulator.eventLog.size()} 건")
            } else {
                println("교환 완주: requestId=$requestId, 오간 메시지 ${simulator.eventLog.size()} 건")
            }
        }
    }

    /** S02 원격 개시 대기 모드. 값을 받지 않는 인자라 [parse] 에 넣지 않는다. */
    private const val REMOTE_START_FLAG = "--remote-start"

    /** F6 재접속 재전송. 값을 받지 않는 인자라 [parse] 에 넣지 않는다. */
    private const val FAULT_F6_FLAG = "--fault-f6"

    /**
     * F6 — 출고 직전에 끊기고, 재접속해 같은 messageId 로 되보낸다.
     *
     * 판정 대상은 **상대 CSMS** 다. 같은 `(stationId, messageId)` 로 두 번 온 `BatteryIn` 을
     * 멱등하게 처리하면 저장된 응답이 다시 오고 장부는 그대로여야 한다. 재전송에 대한 응답이
     * 오지 않으면 상대가 그 프레임을 조용히 버렸다는 뜻이고, 그것도 결과다.
     */
    private suspend fun runF6(simulator: StationSimulator, config: StationSimConfig) {
        simulator.boot()

        // 입고까지 가고 출고 직전에 끊긴다.
        try {
            simulator.runSwap()
            error("F6 인데 장애가 주입되지 않았다 — 출고 직전에 끊겼어야 한다")
        } catch (fault: SimulatedFault) {
            println("F6 장애 주입: ${fault.message}")
        }

        val batteryIn = simulator.eventLog.of(config.stationId)
            .lastOrNull {
                it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.BATTERY_SWAP
            }
            ?: error("BatteryIn 이 나가지 않았다 — 되보낼 것이 없다")
        println("F6 첫 BatteryIn: messageId=${batteryIn.messageId}, 응답 ${simulator.repliesTo(batteryIn.messageId)} 건")

        simulator.disconnect()
        simulator.reconnect()
        simulator.resendLastBatterySwap(sameMessageId = true)

        val replies = simulator.repliesTo(batteryIn.messageId)
        println("F6 재전송 뒤: 같은 messageId 에 대한 응답 $replies 건")
        check(replies >= 2) {
            "재전송에 대한 응답이 오지 않았다 — 상대가 재전송을 조용히 버렸다 (messageId=${batteryIn.messageId})"
        }
    }

    /**
     * 기본 시나리오 구성.
     *
     * 앞의 `setSize` 개 슬롯은 비어 있고(투입 대상), 그다음 `setSize` 개 슬롯에는 내줄
     * 배터리가 들어 있다. 나머지 슬롯은 비워 둔다. **입고 슬롯과 출고 슬롯이 다르다**는
     * `TC_S_103_CSMS` 의 전제를 그대로 옮긴 것이다.
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
