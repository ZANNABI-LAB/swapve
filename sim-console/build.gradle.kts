// 시뮬레이터 제어 콘솔 — 브라우저에서 스테이션 시뮬레이터를 조종한다. (B24)
// HTTP 서버는 JDK 내장 com.sun.net.httpserver 를 쓴다 — 런타임 의존성 0.
//
// ★ 2026-09-02: 화면은 React + Vite 로 옮겼다. 아래 KDoc 이 "프레임워크도 링크하지
// 않는다" 고 못 박고 있던 자리이므로 그 문장을 여기서 고친다 — 뒤집힌 판단을 원래 자리에
// 남겨 두면 다음 사람이 거짓 확신을 얻는다.

plugins {
    application
    alias(libs.plugins.node.gradle)
}

dependencies {
    // 남의 라이브러리가 내는 "SLF4J providers were found" 경고를 끄는 no-op 바인딩.
    // 실행 파일로 배포되는 모듈에만 붙인다 — 라이브러리(`ocpp-core`·`swap-domain`)에 넣으면
    // 그것을 쓰는 남의 애플리케이션의 로깅 선택을 우리가 대신 해 버린다.
    runtimeOnly(libs.slf4j.nop)

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
 * 않고 HTTP 는 JDK 내장 `com.sun.net.httpserver` 로 끝낸다. **이 판정은 그대로다** —
 * 바뀐 것은 화면 쪽뿐이다.
 *
 * ★ **화면은 React 로 쓰고 `vite-plugin-singlefile` 이 HTML 한 장으로 인라인한다.**
 * 손으로 DOM 을 쌓던 657줄이 컴포넌트가 됐고, 상태→화면 매핑을 시험할 수 있게 됐다.
 * **"한 장" 이라는 성질은 유지된다** — 서버는 여전히 클래스패스의 `/console/index.html`
 * 하나만 서빙하고(`SimConsoleServer.staticRoute`), 배포물도 그대로다.
 *
 * **네트워크 없이 떠야 한다는 요구도 그대로다.** 번들은 자기 안에 전부 들어 있으므로
 * CDN·폰트를 링크하지 않는다 — 그 사실은 아래 `checkNoForbiddenDependencies` 가
 * **빌드 산출물을 훑어** 확인한다(소스가 아니라 산출물을 보는 이유는 그쪽이 실제로
 * 배포되는 물건이기 때문이다).
 *
 * **Node 는 Gradle 이 내려받는다**(`download.set(true)`). README 가 약속한
 * *"필요한 것은 JDK 17 과 git 뿐"* 을 깨지 않기 위해서다.
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

/**
 * 화면이 밖을 참조하면 네트워크 없는 곳에서 깨진다. **번들 산출물**을 훑어 확인한다.
 *
 * ⚠️ **문자열이 아니라 "가져오는 구문"을 찾는다.** 예전에는 `http://` 가 한 번이라도
 * 나오면 걸렸는데, React 번들에는 **네트워크를 쓰지 않는 URL 문자열**이 들어 있다 —
 * `http://www.w3.org/2000/svg` 같은 **XML 네임스페이스 식별자**(`createElementNS` 의
 * 인자다)와 에러 메시지 안내 링크다. 그것까지 막으면 검사가 지키려는 것(네트워크 없이
 * 뜨는가)과 무관한 이유로 빨개진다.
 *
 * 그래서 **실제로 자원을 가져오는 자리만** 본다 — `src=`/`href=` 속성, `@import`,
 * CSS `url()`, 그리고 런타임에 절대 URL 로 요청하는 `fetch(`/`XMLHttpRequest.open(`.
 * 검사를 느슨하게 한 것이 아니라 **겨냥을 옮긴 것**이다.
 */
val forbiddenAssetPatterns = mapOf(
    """(?:src|href)\s*=\s*["']https?://""" to "외부 자원을 링크하지 않는다 — 네트워크 없이도 떠야 한다",
    """@import\s+(?:url\()?\s*["']?https?://""" to "외부 스타일을 가져오지 않는다 — 네트워크 없이도 떠야 한다",
    """url\(\s*["']?https?://""" to "외부 자원을 CSS 로 가져오지 않는다 — 네트워크 없이도 떠야 한다",
    """(?:fetch|\.open)\s*\(\s*["'`]https?://""" to "런타임에 밖으로 요청하지 않는다 — 네트워크 없이도 떠야 한다",
    """["'](?:https?:)?//cdn\.""" to "CDN 을 링크하지 않는다 — 네트워크 없이도 떠야 한다",
)

val checkNoForbiddenDependencies by tasks.registering {
    // 산출물을 훑는 검사이므로 **그것을 만드는 태스크에 의존해야 한다.** 걸지 않으면
    // `build` 안에서 순서가 어긋나 "훑을 산출물이 없다"로 빨개진다 — 실제로 걸렸다.
    dependsOn("viteBuild")

    val compileClasspath = configurations.named("compileClasspath")
    val allowed = allowedCompileDependencies
    val mainSources = layout.projectDirectory.dir("src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    // 소스가 아니라 **빌드 산출물**을 본다 — 실제로 배포되는 물건이 그쪽이다.
    val assets = layout.buildDirectory.dir("ui").map { it.asFileTree.matching { include("**/*.html") } }
    val patterns = forbiddenMainPatterns
    val assetPatterns = forbiddenAssetPatterns

    inputs.files(mainSources)
    inputs.files(assets)

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

        fun scan(files: Iterable<File>, needles: Map<String, String>, asRegex: Boolean) =
            files.sortedBy { it.path }.flatMap { file ->
                file.readLines().withIndex().flatMap { (index, line) ->
                    needles.entries
                        .filter { (needle, _) -> if (asRegex) Regex(needle).containsMatchIn(line) else needle in line }
                        .map { (_, reason) -> "${file.name}:${index + 1} — $reason" }
                }
            }

        // 산출물이 하나도 안 잡히면 이 검사는 아무것도 지키지 않는다 — 훑은 수를 함께 본다.
        // (csms 의 checkNoExternalAssets 가 같은 이유로 같은 검사를 갖고 있다.)
        val assetFiles = assets.get().files
        check(assetFiles.isNotEmpty()) {
            "훑을 화면 산출물이 없다 — viteBuild 가 돌지 않았거나 경로가 바뀌었다"
        }

        val offendingLines =
            scan(mainSources.files, patterns, asRegex = false) + scan(assetFiles, assetPatterns, asRegex = true)

        check(offendingLines.isEmpty()) {
            "sim-console 이 금지된 것을 참조한다:\n" + offendingLines.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoForbiddenDependencies)
}

/**
 * ★ **화면 빌드 체인** — React + Vite 를 Gradle 안으로 들인다.
 *
 * **Node 를 이 플러그인이 내려받는다**(`download.set(true)`). 그래야 README 의
 * *"필요한 것은 JDK 17 과 git 뿐"* 이 계속 사실이다 — Gradle 배포판을 래퍼가 받아오는
 * 것과 같은 방식이고, 기계마다 다른 Node 를 쓰지 않으므로 산출물도 흔들리지 않는다.
 *
 * `npmCi` 를 쓴다(`npmInstall` 이 아니라) — `package-lock.json` 그대로 설치해야
 * 빌드가 재현된다.
 */
node {
    version.set(libs.versions.nodejs)
    download.set(true)
    nodeProjectDir.set(layout.projectDirectory.dir("ui"))
    // `npm install` 이 아니라 `npm ci` 로 설치한다 — lock 그대로여야 빌드가 재현된다.
    npmInstallCommand.set("ci")
}

/**
 * 화면을 **HTML 한 장**으로 굽는다 → `build/ui/index.html`.
 *
 * 입출력을 명시해 Gradle 이 최신성을 판단하게 한다 — 화면을 안 건드린 빌드에서 npm 이
 * 매번 도는 것은 순비용이다.
 */
val viteBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    group = "build"
    description = "콘솔 화면(React)을 정적 HTML 한 장으로 굽는다"

    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "build"))

    val ui = layout.projectDirectory.dir("ui")
    inputs.dir(ui.dir("src"))
    inputs.files(ui.file("index.html"), ui.file("vite.config.ts"), ui.file("tsconfig.json"))
    inputs.files(ui.file("package.json"), ui.file("package-lock.json"))
    outputs.dir(layout.buildDirectory.dir("ui"))
}

/**
 * 구운 화면을 `/console/index.html` 로 넣는다 — **서버 코드는 그대로다.**
 * `SimConsoleServer.staticRoute` 는 예전과 똑같이 클래스패스에서 그 한 파일을 읽는다.
 */
tasks.named<ProcessResources>("processResources") {
    dependsOn(viteBuild)
    into("console") {
        from(layout.buildDirectory.dir("ui"))
    }
}

/** 화면 시험(Vitest)도 `check` 에 매단다 — **게이트를 늘리지 않는다.** */
val viteTest by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    group = "verification"
    description = "콘솔 화면의 컴포넌트·로직 시험 (Vitest)"

    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "test"))

    val ui = layout.projectDirectory.dir("ui")
    inputs.dir(ui.dir("src"))
    inputs.files(ui.file("package.json"), ui.file("package-lock.json"), ui.file("vite.config.ts"))
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(viteTest)
}
