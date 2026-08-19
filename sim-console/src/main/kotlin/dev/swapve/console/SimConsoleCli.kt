package dev.swapve.console

import java.util.concurrent.CountDownLatch

/**
 * 실행 진입점 — 콘솔을 띄우고 브라우저를 기다린다.
 *
 * ```
 * ./gradlew :sim-console:run --args="--port 8090 --csms-url ws://localhost:8080/ocpp"
 * ```
 *
 * `station-sim` 의 CLI 를 **대체하지 않는다.** 저쪽은 붙어서 교환 1건을 완주하고 끝나는
 * 일회성 프로세스이고, 이쪽은 그 시뮬레이터를 여러 대 세워 놓고 조종하는 화면이다.
 */
object SimConsoleCli {

    private val USAGE = """
        sim-console — 스테이션 시뮬레이터 제어 콘솔 (데모용)

          --port <n>        콘솔이 뜰 포트. 기본 ${SimConsoleServer.DEFAULT_PORT}
                            (CSMS 의 8080 과 부딪히지 않는 값이어야 한다)
          --csms-url <url>  화면에 채워 둘 CSMS 엔드포인트 (스테이션 식별자 제외).
                            기본 ${SimConsoleServer.DEFAULT_CSMS_URL}
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            println(USAGE)
            return
        }

        val options = parse(args)
        val port = options["port"]?.let { it.toIntOrNull() ?: error("숫자가 아닌 값: --port $it") }
            ?: SimConsoleServer.DEFAULT_PORT
        val csmsUrl = options["csms-url"] ?: SimConsoleServer.DEFAULT_CSMS_URL

        val server = SimConsoleServer(ControlledStations(csmsUrl), port).start()
        // 콘솔은 스테이션을 붙들고 있는 프로세스다. 내려갈 때 소켓을 닫아 주지 않으면
        // CSMS 쪽에는 죽은 연결이 남는다.
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })

        println("sim-console → http://localhost:${server.port} (CSMS 기본값 $csmsUrl)")
        println("브라우저로 열어 스테이션을 붙이십시오. 종료는 Ctrl+C.")

        // 서버 스레드는 전부 데몬이다 — 여기서 막지 않으면 JVM 이 곧바로 끝난다.
        // 사람이 Ctrl+C 로 끊을 때까지 기다린다.
        CountDownLatch(1).await()
    }

    /** `--key value` 짝만 읽는다. 외부 파서 라이브러리를 쓰지 않는다 — `station-sim` 과 같은 규칙이다. */
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
}
