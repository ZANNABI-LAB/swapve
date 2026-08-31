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
        station-sim — an OCPP 2.1 battery swap station simulator

          --csms-url <url>          CSMS endpoint, without the station id. Default ws://localhost:8080/ocpp
          --station-id <id>         Station identifier. Default CS001
          --username <value>        Basic auth user name. Defaults to the station id
          --password <value>        Basic auth password. Omit it and no Authorization header is sent
          --slots <n>               Number of slots (= EVSEs). Default 4
          --set-size <n>            Batteries moved per exchange. Default 2
          --swap-order <In-Out|Out-In>  Exchange order. Default In-Out
          --id-token <value>        Authorization token for the swap. Default RFID-0001
          --id-token-type <type>    Token type. Default ISO14443
          --charging-id-token <v>   Separate token for the charging transactions
                                    (BatterySwapCtrlr.IdToken). Omit it and they are reported
                                    as unauthorized transactions
          --request-id <n>          Correlation number for the swap. Default 1001.
                                    With --remote-start the CSMS value overwrites it (S02.FR.02)
          --remote-start            ★ S02 — do not start on our own; wait for the CSMS to send
                                    a RequestBatterySwapRequest. This is the standard use case,
                                    an app asking for the swap:
                                      POST /api/swaps {"stationId": ..., "idToken": ...}
          --fault-f6                ★ F6 — reproduce a connection lost just before the battery
                                    goes out. Run as far as BatteryIn, drop, reconnect, and
                                    resend BatteryIn **under the same messageId**. A CSMS with
                                    an idempotency ledger replays its stored response and its
                                    books do not grow.
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
            "$REMOTE_START_FLAG and $FAULT_F6_FLAG cannot be used together — F6 is a station-initiated path"
        }
        val options = parse(args.filterNot { it == REMOTE_START_FLAG || it == FAULT_F6_FLAG }.toTypedArray())
        val config = buildConfig(options)

        println("station-sim → ${config.connectUrl} (order ${config.swapOrder.wireValue}, ${config.insertSlots.size} batteries)")

        val faults = if (faultF6) {
            FaultInjection.failingAt(SimStep.BATTERY_OUT, "the connection dropped mid-exchange")
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
            System.err.println("station-sim stopped: ${fault.message}")
            exitProcess(1)
        } catch (failed: CallFailed) {
            System.err.println("station-sim stopped: ${failed.message}")
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
                    println("Waiting for S02 — the CSMS has to send RequestBatterySwap.")
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
                println("F6 complete: requestId=$requestId, ${simulator.eventLog.size()} messages exchanged")
            } else {
                println("Exchange complete: requestId=$requestId, ${simulator.eventLog.size()} messages exchanged")
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
            error("F6 ran without the fault firing — the drop should have happened just before BatteryOut")
        } catch (fault: SimulatedFault) {
            println("F6 fault fired: ${fault.message}")
        }

        val batteryIn = simulator.eventLog.of(config.stationId)
            .lastOrNull {
                it.direction == MessageDirection.OUTBOUND && it.action == BatterySwapWire.BATTERY_SWAP
            }
            ?: error("no BatteryIn went out — there is nothing to resend")
        println("F6 first BatteryIn: messageId=${batteryIn.messageId}, ${simulator.repliesTo(batteryIn.messageId)} replies")

        simulator.disconnect()
        simulator.reconnect()
        simulator.resendLastBatterySwap(sameMessageId = true)

        val replies = simulator.repliesTo(batteryIn.messageId)
        println("F6 after the resend: $replies replies to that same messageId")
        check(replies >= 2) {
            "no answer to the resend — the peer dropped it silently (messageId=${batteryIn.messageId})"
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
            "not enough slots: $setSize to insert + $setSize to dispense = ${setSize * 2} needed, but there are $slotCount"
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
            ?: error("unknown swap order: $value (In-Out or Out-In)")
    }

    /** `--key value` 짝만 읽는다. 외부 파서 라이브러리를 쓰지 않는다 — 의존성 0 이 원칙이다. */
    private fun parse(args: Array<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            require(token.startsWith("--")) { "unknown argument: $token\n\n$USAGE" }
            require(index + 1 < args.size) { "argument without a value: $token\n\n$USAGE" }
            options[token.removePrefix("--")] = args[index + 1]
            index += 2
        }
        return options
    }

    private fun Map<String, String>.int(key: String, fallback: Int): Int =
        this[key]?.let { it.toIntOrNull() ?: error("not a number: --$key $it") } ?: fallback
}
