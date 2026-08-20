// 스테이션 시뮬레이터 — 슬롯 · 배터리 · 장애 주입. (M6)
// 전송은 JDK 내장 java.net.http.WebSocket 을 쓴다 — 외부 의존성 0.

plugins {
    application
}

dependencies {
    implementation(project(":ocpp-core"))
    implementation(project(":swap-domain"))
}

application {
    mainClass.set("dev.swapve.station.StationSimCli")
}

/**
 * **JDK 와 ocpp-core 로 끝낸다** (PLAN §6 기술선택 — 시뮬레이터 의존성 0).
 *
 * 프레이밍·스키마 검증·멱등·직렬화는 `ocpp-core` 를 그대로 쓰고 (§6 원칙 3), 전송은 JDK
 * 내장 WebSocket 을 쓴다. 외부 WebSocket/JSON 라이브러리는 들어올 수 없다.
 *
 * Jackson 과 coroutines 가 목록에 있는 것은 우리가 고른 것이 아니라 `ocpp-core` 가 공개 API
 * 로 노출한 추이 의존성이기 때문이다 — 같은 것을 시뮬레이터가 **다시** 고르지 않았다는 것이
 * 요점이다.
 *
 * 산문으로 적어두면 지켜졌는지 알 수 없으므로 `check` 에 물려 기계 검증한다.
 * (`swap-domain:checkNoExternalDependencies` · `ocpp-core:checkNoFrameworkImports` 와 같은 취지다.)
 */
// 좌표를 하드코딩하지 않는다 — 그룹 ID 는 배포(B07)를 하며 바뀌었고, 또 바뀔 수 있다.
val internalGroup = rootProject.group

val allowedCompileDependencies = setOf(
    // 이 저장소의 모듈. 시뮬레이터가 기대는 유일한 코드다
    "$internalGroup:ocpp-core",
    "$internalGroup:swap-domain",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
    // ocpp-core 가 api 로 노출하는 것들
    "com.fasterxml.jackson.core:jackson-databind",
    "com.fasterxml.jackson.core:jackson-core",
    "com.fasterxml.jackson.core:jackson-annotations",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
)

/** 소스에 나타나면 안 되는 것들. 이유는 값이 아니라 문장으로 남긴다. */
val forbiddenMainPatterns = mapOf(
    "org.springframework" to "시뮬레이터는 Spring 을 쓰지 않는다 (PLAN §6)",
    "io.netty" to "Netty 를 쓰지 않는다 (PLAN §6)",
    "okhttp3" to "외부 WebSocket 라이브러리를 쓰지 않는다 — JDK 내장을 쓴다",
    "org.java_websocket" to "외부 WebSocket 라이브러리를 쓰지 않는다 — JDK 내장을 쓴다",
    "jakarta.websocket" to "컨테이너 WebSocket API 를 쓰지 않는다 — JDK 내장을 쓴다",
    "javax.websocket" to "컨테이너 WebSocket API 를 쓰지 않는다 — JDK 내장을 쓴다",
    "com.google.gson" to "외부 JSON 라이브러리를 쓰지 않는다 — ocpp-core 가 쓰는 것을 그대로 쓴다",
    "kotlinx.serialization" to "외부 JSON 라이브러리를 쓰지 않는다 — ocpp-core 가 쓰는 것을 그대로 쓴다",
)

val checkNoForbiddenDependencies by tasks.registering {
    val compileClasspath = configurations.named("compileClasspath")
    val allowed = allowedCompileDependencies
    val mainSources = layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    val patterns = forbiddenMainPatterns

    inputs.files(mainSources)

    doLast {
        val offendingModules = compileClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
            .filterNot { it in allowed }
            .distinct()
            .sorted()

        check(offendingModules.isEmpty()) {
            "station-sim 은 JDK 와 ocpp-core 로 끝낸다 (PLAN §6): ${offendingModules.joinToString()}"
        }

        val offendingLines = mainSources.files.sorted().flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                patterns.entries
                    .filter { (needle, _) -> needle in line }
                    .map { (_, reason) -> "${file.name}:${index + 1} — $reason" }
            }
        }

        check(offendingLines.isEmpty()) {
            "station-sim 이 금지된 라이브러리를 참조한다 (PLAN §6):\n" + offendingLines.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoForbiddenDependencies)
}
