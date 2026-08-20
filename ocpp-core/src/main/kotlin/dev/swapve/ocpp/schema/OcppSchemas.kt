package dev.swapve.ocpp.schema

/**
 * OCA 공식 OCPP 2.1 JSON Schema 원문에 대한 클래스패스 접근자.
 *
 * 스키마는 코드로 옮겨 적지 않는다 (설계원칙 2). 표준이 바뀌면 `schemas/` 의
 * `.json` 파일만 교체하면 된다.
 *
 * 이름은 OCPP action 이름 + `Request`/`Response` 다. 예: `BatterySwapRequest`.
 */
object OcppSchemas {

    private const val ROOT = "ocpp/schemas"
    private const val INDEX = "$ROOT/_index.txt"

    /** 클래스패스에 존재하는 스키마 이름 전체 (정렬됨). */
    val names: List<String> by lazy {
        val index = resource(INDEX)
            ?: error("스키마 인덱스를 찾을 수 없다: $INDEX — ocpp-core 의 syncOcppSchemas 태스크를 확인할 것")
        index.reader().readLines().filter { it.isNotBlank() }
    }

    /** 스키마 원문. 없으면 [IllegalArgumentException]. */
    fun read(name: String): String {
        val stream = resource("$ROOT/$name.json")
            ?: throw IllegalArgumentException("알 수 없는 OCPP 스키마: $name")
        return stream.use { it.readBytes().decodeToString() }
    }

    fun contains(name: String): Boolean = name in names

    private fun resource(path: String) = OcppSchemas::class.java.classLoader.getResourceAsStream(path)
}
