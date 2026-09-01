package dev.swapve.console

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

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

    internal val USAGE = """
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

    /**
     * 읽을 줄 아는 것 전부. **모르는 이름은 조용히 무시하지 않는다** —
     * `--prot 9000` 은 오타지만 파서에게는 `--` 로 시작하는 정상적인 짝이라, 예전에는
     * 아무 말 없이 기본 포트로 떴다. 준 값이 무시된 것을 준 사람이 알 길이 없었다.
     */
    internal val KNOWN = setOf("port", "bind", "csms-url", "user", "password")

    /** 파싱이 끝난 뒤의 실행 조건. 시험이 [options] 로 이것만 따로 확인한다. */
    internal data class Options(
        val port: Int,
        val csmsUrl: String,
        val bindAddress: String,
        val credentials: SimConsoleServer.Credentials?,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--help" || it == "-h" }) {
            println(USAGE)
            return
        }

        // 잘못 준 인자는 스택트레이스가 아니라 안내로 답한다. 배포물을 받은 사람이
        // 처음 보는 화면이 우리 결함처럼 읽히면 안 된다.
        val options = try {
            options(args)
        } catch (invalid: IllegalArgumentException) {
            System.err.println(invalid.message)
            // 종료 코드 0 으로 끝나면 감시자·CI 단계가 뜨지 않은 콘솔을 성공으로 읽는다.
            // `station-sim` 이 같은 실패에 쓰는 코드와 같다.
            exitProcess(1)
        }

        val server = try {
            SimConsoleServer(
                ControlledStations(options.csmsUrl),
                options.port,
                options.bindAddress,
                options.credentials,
            ).start()
        } catch (failure: IllegalArgumentException) {
            System.err.println(failure.message)
            exitProcess(1)
        }
        val bindAddress = options.bindAddress
        val csmsUrl = options.csmsUrl
        // 콘솔은 스테이션을 붙들고 있는 프로세스다. 내려갈 때 소켓을 닫아 주지 않으면
        // CSMS 쪽에는 죽은 연결이 남는다.
        Runtime.getRuntime().addShutdownHook(Thread { server.close() })

        println("sim-console → http://$bindAddress:${server.port} (CSMS default $csmsUrl)")
        println("Open it in a browser and attach a station. Ctrl+C to stop.")

        // 서버 스레드는 전부 데몬이다 — 여기서 막지 않으면 JVM 이 곧바로 끝난다.
        // 사람이 Ctrl+C 로 끊을 때까지 기다린다.
        CountDownLatch(1).await()
    }

    /** 인자를 실행 조건으로 바꾼다. 성립하지 않으면 [IllegalArgumentException]. */
    internal fun options(args: Array<String>): Options {
        val given = parse(args)

        val port = given["port"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("not a number: --port $it\n\n$USAGE")
        } ?: SimConsoleServer.DEFAULT_PORT

        val user = given["user"]
        val password = given["password"]
        require((user == null) == (password == null)) {
            "--user and --password must be given together\n\n$USAGE"
        }

        return Options(
            port = port,
            csmsUrl = given["csms-url"] ?: SimConsoleServer.DEFAULT_CSMS_URL,
            bindAddress = given["bind"] ?: SimConsoleServer.DEFAULT_BIND_ADDRESS,
            credentials = user?.let { SimConsoleServer.Credentials(it, requireNotNull(password)) },
        )
    }

    /** `--key value` 짝만 읽는다. 외부 파서 라이브러리를 쓰지 않는다 — `station-sim` 과 같은 규칙이다. */
    private fun parse(args: Array<String>): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            require(token.startsWith("--")) { "unknown argument: $token\n\n$USAGE" }
            require(index + 1 < args.size) { "argument without a value: $token\n\n$USAGE" }
            val key = token.removePrefix("--")
            require(key in KNOWN) {
                "unknown option: $token (known: ${KNOWN.joinToString { "--$it" }})\n\n$USAGE"
            }
            options[key] = args[index + 1]
            index += 2
        }
        return options
    }
}
