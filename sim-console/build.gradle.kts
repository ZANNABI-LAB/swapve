// 시뮬레이터 제어 콘솔 — 브라우저에서 스테이션 시뮬레이터를 조종한다. (B24)
// HTTP 서버는 JDK 내장 com.sun.net.httpserver 를 쓴다 — 외부 의존성 0.

plugins {
    application
}

dependencies {
    // 조종당하는 쪽. 의존 방향은 sim-console → station-sim 한 방향이고, 반대는 없다.
    implementation(project(":station-sim"))
    // station-sim 이 implementation 으로 감춘 둘을 여기서 **다시 선언한다.** 콘솔이 직접
    // 쓰는 타입이기 때문이다 — 인가 토큰(IdToken)과 이벤트 로그(오간 메시지 수 · 거부 관측).
    // 남의 추이 의존에 얹혀 컴파일되는 것과 내가 쓴다고 적는 것은 다른 일이다.
    implementation(project(":ocpp-core"))
    implementation(project(":swap-domain"))
}

application {
    mainClass.set("dev.swapve.console.SimConsoleCli")
}

/**
 * **JDK 와 station-sim 으로 끝낸다** (기술선택).
 *
 * 콘솔은 시험계를 조종하는 화면이지 또 하나의 서버가 아니다. Spring 도 Ktor 도 들이지
 * 않고 HTTP 는 JDK 내장 `com.sun.net.httpserver` 로, 화면은 정적 HTML 한 장으로 끝낸다 —
 * 네트워크 없는 곳에서도 떠야 하므로 CDN·폰트·프레임워크도 링크하지 않는다.
 *
 * Jackson 과 coroutines 가 목록에 있는 것은 우리가 고른 것이 아니라 `ocpp-core` 가 공개 API
 * 로 노출한 추이 의존성이기 때문이다 — `station-sim` 의 같은 이름 태스크와 같은 사정이다.
 *
 * 산문으로 적어두면 지켜졌는지 알 수 없으므로 `check` 에 물려 기계 검증한다.
 * (`station-sim:checkNoForbiddenDependencies` 와 **같은 취지의 같은 검사**다.)
 */
// 좌표를 하드코딩하지 않는다 — `station-sim` 의 같은 검사와 같은 사정이다.
val internalGroup = rootProject.group

val allowedCompileDependencies = setOf(
    // 이 저장소의 모듈. 콘솔이 기대는 유일한 코드다
    "$internalGroup:station-sim",
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
    "org.springframework" to "콘솔은 Spring 을 쓰지 않는다 — HTTP 는 JDK 내장 서버다",
    "io.ktor" to "콘솔은 Ktor 를 쓰지 않는다 — HTTP 는 JDK 내장 서버다",
    "io.netty" to "Netty 를 쓰지 않는다",
    "okhttp3" to "외부 HTTP 라이브러리를 쓰지 않는다 — JDK 내장을 쓴다",
    "javax.servlet" to "서블릿 컨테이너를 쓰지 않는다 — JDK 내장 HTTP 서버를 쓴다",
    "jakarta.servlet" to "서블릿 컨테이너를 쓰지 않는다 — JDK 내장 HTTP 서버를 쓴다",
    "com.google.gson" to "외부 JSON 라이브러리를 쓰지 않는다 — ocpp-core 가 쓰는 것을 그대로 쓴다",
    "kotlinx.serialization" to "외부 JSON 라이브러리를 쓰지 않는다 — ocpp-core 가 쓰는 것을 그대로 쓴다",
)

/** 화면이 밖을 참조하면 네트워크 없는 곳에서 깨진다. 정적 파일도 같은 이유로 검사한다. */
val forbiddenAssetPatterns = mapOf(
    "http://" to "외부 자원을 링크하지 않는다 — 네트워크 없이도 떠야 한다",
    "https://" to "외부 자원을 링크하지 않는다 — 네트워크 없이도 떠야 한다",
    "cdn." to "CDN 을 링크하지 않는다 — 네트워크 없이도 떠야 한다",
)

val checkNoForbiddenDependencies by tasks.registering {
    val compileClasspath = configurations.named("compileClasspath")
    val allowed = allowedCompileDependencies
    val mainSources = layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    val assets = layout.projectDirectory.dir("src/main/resources").asFileTree.matching { include("**/*.html") }
    val patterns = forbiddenMainPatterns
    val assetPatterns = forbiddenAssetPatterns

    inputs.files(mainSources, assets)

    doLast {
        val offendingModules = compileClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
            .filterNot { it in allowed }
            .distinct()
            .sorted()

        check(offendingModules.isEmpty()) {
            "sim-console 은 JDK 와 station-sim 으로 끝낸다: ${offendingModules.joinToString()}"
        }

        fun scan(files: Iterable<File>, needles: Map<String, String>) = files.sortedBy { it.path }.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                needles.entries
                    .filter { (needle, _) -> needle in line }
                    .map { (_, reason) -> "${file.name}:${index + 1} — $reason" }
            }
        }

        val offendingLines = scan(mainSources.files, patterns) + scan(assets.files, assetPatterns)

        check(offendingLines.isEmpty()) {
            "sim-console 이 금지된 것을 참조한다:\n" + offendingLines.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoForbiddenDependencies)
}
