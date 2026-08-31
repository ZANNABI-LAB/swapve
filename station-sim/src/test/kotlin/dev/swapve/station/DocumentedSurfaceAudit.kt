package dev.swapve.station

import java.io.File

/**
 * ★★ **문서가 "전부 적었다"고 말하면 그것이 사실인지 센다** (L1 단위 게이트).
 *
 * `docs/VIRTUAL-STATION.md` §2 는 이렇게 시작한다:
 *
 * > *All 24 public functions and 7 public properties of `StationSimulator`, with nothing left out.*
 *
 * 이 한 문장은 **세 가지를 동시에 주장한다** — 함수가 24개다, 속성이 7개다, 빠진 것이 없다.
 * 셋 다 코드가 바뀌면 조용히 거짓이 되는데, **게이트 여섯 개가 전부 Gradle/JVM 이라 마크다운을
 * 한 번도 열지 않는다.** 실제로 이틀 사이에 두 번 낡았다: `4 → 6` 으로 고친 다음 날
 * 속성이 하나 늘어 `6 → 7` 을 또 고쳐야 했다. 두 번 다 사람이 우연히 잡았다.
 *
 * ### 왜 개수만 세지 않는가
 *
 * 개수 대조만 하면 **이름이 바뀐 것을 못 잡는다.** 멤버 하나를 지우고 다른 하나를 더하면
 * 합계는 그대로다. 그래서 이름 하나하나가 문서 어딘가에 `` `name` `` 으로 있는지도 본다.
 * 그것이 *"with nothing left out"* 이 실제로 주장하는 바다.
 *
 * ### 무엇을 못 보는가
 *
 * - **설명이 맞는지는 모른다.** `slotState(slotId)` 가 표에 있다는 것만 알지, 그 옆 칸의
 *   설명이 참인지는 사람이 읽어야 한다. 이 감사는 **목록의 완전성**만 본다.
 * - **다른 문서·다른 클래스는 안 본다.** 완전성을 명시적으로 주장하는 자리가 여기 하나라서
 *   여기만 센다. 같은 주장이 다른 곳에 생기면 그때 넓힌다.
 * - **`private`/`internal` 을 접두로 판정한다.** 가시성 수식어가 줄 앞에 오지 않는 문법이
 *   생기면 이 판정이 틀린다. 코틀린에서 그런 자리는 지금 없다.
 *
 * ### 경로를 추정하지 않는다
 *
 * [sourceFile] 과 [docFile] 은 Gradle 이 **입력으로 선언한 바로 그 경로**를 그대로 받는다.
 * 시험이 작업 디렉토리에서 추정하면 빗나갔을 때 0 개를 세고도 통과한다 —
 * `WireLanguageAudit` 가 같은 이유로 같은 방식을 쓴다.
 */
class DocumentedSurfaceAudit(
    private val sourceFile: File,
    private val docFile: File,
) {

    /** 감사 결과. [violations] 가 비어 있으면 통과다. [checked] 는 실제로 센 멤버 수다. */
    data class Result(val checked: Int, val violations: List<String>) {
        val passed: Boolean get() = violations.isEmpty()
    }

    fun run(): Result {
        val source = sourceFile.readText()
        val doc = docFile.readText()

        val functions = declarationsIn(source, FUNCTION)
        val properties = declarationsIn(source, PROPERTY)

        val violations = mutableListOf<String>()

        claimedCounts(doc).let { claimed ->
            if (claimed == null) {
                violations += "완전성 주장 문장을 찾지 못했다 — 형태가 바뀌었으면 이 감사도 함께 고쳐야 한다"
            } else {
                val (claimedFunctions, claimedProperties) = claimed
                if (claimedFunctions != functions.size) {
                    violations += "함수 개수: 문서는 $claimedFunctions, 소스는 ${functions.size} " +
                        "(${functions.joinToString()})"
                }
                if (claimedProperties != properties.size) {
                    violations += "속성 개수: 문서는 $claimedProperties, 소스는 ${properties.size} " +
                        "(${properties.joinToString()})"
                }
            }
        }

        (functions + properties)
            .filterNot { name -> doc.contains("`$name") }
            .forEach { name -> violations += "문서에 없다: $name — \"with nothing left out\" 이 거짓이 된다" }

        return Result(checked = functions.size + properties.size, violations = violations)
    }

    private fun declarationsIn(source: String, kind: Regex): List<String> =
        source.lineSequence()
            .mapNotNull { kind.find(it)?.groupValues?.get(1) }
            .toList()

    private fun claimedCounts(doc: String): Pair<Int, Int>? =
        CLAIM.find(doc)?.groupValues?.let { it[1].toInt() to it[2].toInt() }

    private companion object {

        /**
         * 클래스 본문의 **최상위 선언만** 본다 — 들여쓰기가 정확히 네 칸인 줄이다.
         *
         * 중첩 클래스와 `companion object` 의 내용은 여덟 칸이라 저절로 빠지고, 생성자
         * 프로퍼티(`val config`)는 클래스 헤더에 있지만 같은 네 칸이라 들어온다. 그 둘이
         * 문서 배치표에 함께 실리므로 그것이 맞다.
         */
        val FUNCTION = Regex("^ {4}(?!private|internal)(?:suspend |override |operator |inline )*fun ([a-zA-Z][a-zA-Z0-9_]*)")
        val PROPERTY = Regex("^ {4}(?!private|internal)(?:override |lateinit )*(?:val|var) ([a-zA-Z][a-zA-Z0-9_]*)")

        /** *"All 24 public functions and 7 public properties …"* 의 숫자 둘. */
        val CLAIM = Regex("All (\\d+) public functions and (\\d+) public properties")
    }
}
