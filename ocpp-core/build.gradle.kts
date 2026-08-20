// OCPP-J 프레이밍 · 공식 JSON Schema 검증 · 세션 계층. 프레임워크를 모른다.

dependencies {
    api(libs.jackson.databind)
    // 세션 계층의 공개 API 가 suspend 함수와 Duration 을 쓴다 (M4)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.json.schema.validator)

    testImplementation(libs.kotlinx.coroutines.test)
    // 이벤트 로그만으로 파생 상태를 재구성할 수 있음을 증명하는 데 M3 상태머신을 쓴다.
    // 테스트 전용이다 — main 의존 방향은 그대로 ocpp-core → (없음) 이다.
    testImplementation(project(":swap-domain"))
}

// runTest / advanceUntilIdle / currentTime — 가상 시간 API 는 아직 실험 단계로 표시돼 있다.
// 테스트 소스에서만 opt-in 한다 (L1: "테스트가 실제 시간을 기다리지 않는다").
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

/**
 * 이 모듈은 프레임워크도 전송도 모른다 (설계원칙 1).
 *
 * 그리고 **현재 시각을 직접 조회하지 않는다** — 시각과 타임아웃은 주입받는다. 그래야 세션
 * 계층 테스트가 실제로 몇 초를 기다리지 않고 결정적으로 돈다.
 *
 * 산문으로 적어두면 지켜졌는지 알 수 없으므로 `check` 에 물려 기계 검증한다.
 * (swap-domain 의 `checkNoExternalDependencies` 와 같은 취지다.)
 */
val forbiddenMainPatterns = mapOf(
    "org.springframework" to "Spring 을 import 하지 않는다",
    "io.netty" to "Netty 를 import 하지 않는다",
    "javax.servlet" to "서블릿 API 를 import 하지 않는다",
    "jakarta.servlet" to "서블릿 API 를 import 하지 않는다",
    "java.net.http" to "WebSocket/HTTP 클라이언트를 import 하지 않는다",
    "javax.websocket" to "WebSocket API 를 import 하지 않는다",
    "jakarta.websocket" to "WebSocket API 를 import 하지 않는다",
    "Instant.now(" to "현재 시각을 직접 조회하지 않는다 — Clock 을 주입받아라",
    "System.currentTimeMillis" to "현재 시각을 직접 조회하지 않는다 — Clock 을 주입받아라",
    "System.nanoTime" to "현재 시각을 직접 조회하지 않는다 — Clock 을 주입받아라",
)

val checkNoFrameworkImports by tasks.registering {
    val mainSources = layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    val patterns = forbiddenMainPatterns

    inputs.files(mainSources)

    doLast {
        val offenses = mainSources.files.sorted().flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                patterns.entries
                    .filter { (needle, _) -> needle in line }
                    .map { (_, reason) -> "${file.name}:${index + 1} — $reason" }
            }
        }

        check(offenses.isEmpty()) {
            "ocpp-core 는 프레임워크와 벽시계를 모른다:\n" + offenses.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoFrameworkImports)
}

/**
 * 공식 스키마 181개를 클래스패스로 옮긴다.
 *
 * 원문을 수정하지 않고 그대로 복사한다 — CC BY-ND 4.0 (개작 금지). NOTICE 참조.
 *
 * **경로에 패키지 이름을 붙인다.** `ocpp/schemas/` 같은 일반 경로에 두면, 소비자가 다른
 * OCPP 라이브러리를 함께 쓰거나 자기 `resources/ocpp/` 를 가질 때 클래스패스에서 겹친다.
 * 겹치면 `getResourceAsStream` 이 첫 번째 하나만 돌려주므로 **인덱스가 통째로 가려지고
 * 모든 페이로드가 "모르는 action" 으로 거부된다** — 예외가 아니라 오판정이라 추적이 어렵다.
 *
 * 디렉토리 목록은 jar 안에서 열거할 수 없으므로 `_index.txt` 를 함께 생성한다.
 * 판본은 `_version.txt` 에 적는다 — 스키마 원문의 `comment` 가 스스로 밝히는 값이므로,
 * `schemas/` 를 교체하면 버전도 따라온다.
 */
val schemaSourceDir = rootProject.layout.projectDirectory.dir("schemas")
val schemaOutputDir = layout.buildDirectory.dir("generated-resources/schemas")

/** 클래스패스에서 스키마가 놓이는 자리. `OcppSchemas.ROOT` 와 같은 문자열이어야 한다. */
val schemaResourcePath = "dev/swapve/ocpp/schemas"

val syncOcppSchemas by tasks.registering(Sync::class) {
    from(schemaSourceDir) {
        include("*.json")
        into(schemaResourcePath)
    }
    into(schemaOutputDir)

    doLast {
        val target = schemaOutputDir.get().dir(schemaResourcePath).asFile
        val schemas = target.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

        check(schemas.isNotEmpty()) { "스키마가 하나도 복사되지 않았다: ${schemaSourceDir.asFile}" }

        target.resolve("_index.txt")
            .writeText(schemas.joinToString("\n", postfix = "\n") { it.name.removeSuffix(".json") })

        // 판본을 스키마 원문에서 읽는다. 손으로 적으면 교체할 때 어긋난다.
        val commentOf = Regex(""""comment"\s*:\s*"([^"]*)"""")
        val versions = schemas.mapNotNull { commentOf.find(it.readText())?.groupValues?.get(1) }.distinct()

        check(versions.size == 1) {
            "스키마 판본이 섞여 있다 (${versions.size} 종):\n" + versions.joinToString("\n") { "  - $it" }
        }
        target.resolve("_version.txt").writeText(versions.single() + "\n")
    }
}

sourceSets.main {
    resources.srcDir(syncOcppSchemas)
}
