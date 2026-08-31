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
        sim-console — a control console for the station simulator (for demonstration)

          --port <n>        Port for the console. Default ${SimConsoleServer.DEFAULT_PORT}
                            (it must not collide with the CSMS on 8080)
          --bind <addr>     Bind address. Default ${SimConsoleServer.DEFAULT_BIND_ADDRESS}
                            Anything but loopback requires --user and --password
          --csms-url <url>  CSMS endpoint to prefill on screen, without the station id.
                            Default ${SimConsoleServer.DEFAULT_CSMS_URL}
          --user <name>     Basic auth user name for the console
          --password <pw>   Basic auth password for the console
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            println(USAGE)
            return
        }

        val options = parse(args)
        val port = options["port"]?.let { it.toIntOrNull() ?: error("not a number: --port $it") }
            ?: SimConsoleServer.DEFAULT_PORT
        val csmsUrl = options["csms-url"] ?: SimConsoleServer.DEFAULT_CSMS_URL
        val bindAddress = options["bind"] ?: SimConsoleServer.DEFAULT_BIND_ADDRESS
        val user = options["user"]
        val password = options["password"]
        require((user == null) == (password == null)) {
            "--user and --password must be given together\n\n$USAGE"
        }
        val credentials = user?.let { SimConsoleServer.Credentials(it, requireNotNull(password)) }

        val server = try {
            SimConsoleServer(ControlledStations(csmsUrl), port, bindAddress, credentials).start()
        } catch (failure: IllegalArgumentException) {
            System.err.println(failure.message)
            return
        }
        // 콘솔은 스테이션을 붙들고 있는 프로세스다. 내려갈 때 소켓을 닫아 주지 않으면
        // CSMS 쪽에는 죽은 연결이 남는다.
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })

        println("sim-console → http://$bindAddress:${server.port} (CSMS default $csmsUrl)")
        println("Open it in a browser and attach a station. Ctrl+C to stop.")

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
            require(token.startsWith("--")) { "unknown argument: $token\n\n$USAGE" }
            require(index + 1 < args.size) { "argument without a value: $token\n\n$USAGE" }
            options[token.removePrefix("--")] = args[index + 1]
            index += 2
        }
        return options
    }
}
