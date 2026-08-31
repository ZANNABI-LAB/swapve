// 관제 서버 — OCPP-J WebSocket 엔드포인트 · S01 Authorize · 부팅/하트비트. (M5)

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

/**
 * ★ **시험에서는 no-op 로거를 걷어낸다.**
 *
 * `station-sim`·`sim-console` 은 실행 파일로 배포되므로 `slf4j-nop` 을 달고 있다 — 남의
 * 라이브러리가 내는 *"No SLF4J providers were found"* 경고를 끄기 위해서다. 그런데 이 모듈은
 * 그 둘을 **시험 의존으로** 쓰고, `runtimeOnly` 는 시험 런타임까지 따라 들어온다.
 *
 * logback 과 nop 이 한 클래스패스에 있으면 SLF4J 는 **하나만 고르고**, nop 이 이기면 시험이
 * 깨졌을 때 CSMS 로그가 통째로 사라진다. 실패를 진단할 수 없는 시험은 절반만 있는 시험이다.
 * 배포되는 `csms.jar` 에는 애초에 nop 이 없다 — 그쪽은 `runtimeClasspath` 라 전이되지 않는다.
 */
configurations {
    testRuntimeClasspath {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }
}

dependencies {
    implementation(project(":ocpp-core"))
    implementation(project(":swap-domain"))
    implementation(libs.spring.boot.starter.websocket)
    // 보안 프로파일 1 은 BCrypt 해시 검증만 필요하다. starter-security 를 넣으면
    // 필터체인 자동설정이 붙어 WebSocket 핸드셰이크 경계가 두 군데가 된다.
    implementation(libs.spring.security.crypto)
    // OUT_TIMED_OUT 장부는 영속이 필요하다 (불변식) — 인메모리로 끝내면 장부
    // 불균형이 재시작마다 사라진다. JdbcTemplate 하나로 끝내고 JPA·마이그레이션 도구는
    // 넣지 않는다. 스키마는 schema.sql 한 장이다
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.h2)
    // CsmsProperties 의 생성자 바인딩이 Kotlin 주 생성자와 기본값을 읽는 데 쓴다
    implementation(libs.kotlin.reflect)
    // @ConfigurationProperties 메타데이터. 없어도 동작하지만 IDE 자동완성이 생긴다
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    // 성공 기준 S1 — 시뮬레이터와 CSMS 를 한 테스트 안에서 **실제 소켓으로** 붙인다.
    // 시험 전용 의존이다. main 의 의존 방향은 그대로 csms → (ocpp-core, swap-domain) 이고,
    // station-sim 은 Spring 을 여전히 모른다.
    testImplementation(project(":station-sim"))
    // B24 — 제어 콘솔의 API 로 교환이 실제로 완주하는지는 **실제 CSMS 를 상대로** 확인해야
    // 의미가 있다. 위와 같은 사정이고 같은 자리다: 시험 전용 의존이며, main 의 의존 방향은
    // 그대로 csms → (ocpp-core, swap-domain) 이다. sim-console 은 csms 를 모른다.
    testImplementation(project(":sim-console"))
}

/**
 * 시험용 H2 는 저장소 루트가 아니라 `build/` 안에 둔다.
 *
 * 운영 설정(`application.yml`)은 `./data/swapve` 를 가리킨다 — 장부는 파일에 남아야 하고
 *, 그건 시험에서도 마찬가지다. 다만 시험이 만든 파일까지 작업 디렉토리에
 * 쌓일 이유는 없으므로 시스템 프로퍼티로 덮는다. **인메모리로 바꾸지 않는다** — 그러면
 * "재시작 후에도 남는가"를 시험이 확인할 수 없다.
 *
 * 태스크마다 DB 디렉토리를 나누고 시작할 때 비운다. `SwapMetricsService` 가 이벤트 로그
 * 전체를 스캔해 이벤트 로그 전역 지표를 계산하는 것은 운영에서 맞는 동작이지만, 앞선 시험 실행이
 * 남긴 로그까지 다음 실행이 같이 세면 그것은 시험 격리의 결함이다. `conformanceTest` 와
 * `auditTest` 를 매번 실제로 돌리는 이유와 같은 논지다.
 */
tasks.withType<Test>().configureEach {
    val h2Directory = layout.buildDirectory.dir("test-h2/$name").get().asFile
    val h2Database = h2Directory.resolve("swapve")

    systemProperty(
        "spring.datasource.url",
        "jdbc:h2:file:${h2Database.absolutePath};AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
    )
    systemProperty("csms.recovery.enabled", "false")
    systemProperty("csms.retention.enabled", "false")
    // 기본 운영값은 BASIC 이지만 test 태스크의 raw 핸드셰이크 시험과 비인증 경로 시험은
    // 자격증명 목록을 싣지 않는다. 공통 시험 기본값은 NONE 으로 두고, 인증 전용 시험과
    // 적합성·감사 게이트가 BASIC 을 명시적으로 켠다.
    systemProperty("csms.security.profile", "NONE")

    doFirst {
        if (!h2Directory.deleteRecursively()) {
            throw org.gradle.api.GradleException("시험용 H2 디렉토리를 비우지 못했다: $h2Directory")
        }
    }
}

/**
 * ⚠️ **소스를 읽는 시험은 그 파일을 입력으로 선언해야 한다.**
 *
 * `WireLanguageAuditTest` 는 다섯 모듈의 main 소스를 열어 와이어로 나가는 문자열에 한글이
 * 없는지 본다. 그런데 문자열 리터럴만 바꾸면 `csms` 의 바이트코드는 그대로라, 선언이 없으면
 * Gradle 이 `test` 를 up-to-date 로 건너뛴다 — 위반을 넣어도 시험이 아예 돌지 않고 초록으로
 * 보인다. `ocpp-core` 의 `wireConstantsSource` 선언이 같은 사고를 겪고 남은 자리다.
 *
 * 경로는 시험이 작업 디렉토리에서 추정하지 않는다. 추정이 빗나가면 0 개를 스캔하고도
 * 통과로 끝나기 때문에, Gradle 이 **입력으로 선언한 바로 그 경로**를 그대로 넘긴다.
 *
 * 새 게이트를 만들지 않는다 — `:csms:test` 는 `build` 가 이미 도는 자리다.
 */
val wireLanguageSourceRoots = listOf("ocpp-core", "swap-domain", "csms", "station-sim", "sim-console")
    .map { rootProject.layout.projectDirectory.dir("$it/src/main/kotlin") }

tasks.named<Test>("test") {
    inputs.files(wireLanguageSourceRoots.map { it.asFileTree.matching { include("**/*.kt") } })
        .withPropertyName("wireLanguageSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty(
        "swapve.wireLanguage.sourceRoots",
        wireLanguageSourceRoots.joinToString(File.pathSeparator) { it.asFile.absolutePath },
    )
}

/**
 * ★ **L2 표준 적합성 게이트의 실체**.
 *
 * 시험 대상(System under test)이 CSMS 인 Part 6 케이스가 여기서 돈다. 시험계(Test System)
 * 역할은 `station-sim` 이 맡는다 — 시뮬레이터가 스펙 시퀀스를 그대로 연기하고, 우리가
 * 만든 CSMS 가 시험받는다.
 *
 * **`TC_S_104_CS` 는 그 반대다** — 시험 대상이 CS 라 시뮬레이터가 시험받고 CSMS 가
 * 시험계다. 그래도 같은 게이트에 둔다: 두 방향 다 "표준대로 대화하는가"를 묻는 시험이고,
 * 한쪽을 다른 태스크로 빼면 적합성이 어디까지 확인됐는지가 두 곳에 흩어진다.
 *
 * `test` 태스크와 **같은 소스셋을 공유하되 태그로만 갈린다.** 소스셋을 나누면 시험 지원
 * 코드(`SwapScenario`, `FixedClockConfig` …)를 복제하거나 또 다른 공유 소스셋을 만들어야
 * 하는데, 그건 게이트를 나누려다 구조를 늘리는 일이다.
 */
val conformanceTest by tasks.registering(Test::class) {
    group = "verification"
    description = "TC_S_102_CSMS · TC_S_103_CSMS · TC_S_104_CS 와 실패 시나리오 F1~F6 (S2·S3)"

    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform {
        includeTags("conformance")
    }

    systemProperty("csms.security.profile", "BASIC")

    // 적합성은 "지금 코드가 표준을 지키는가"를 묻는다. 앞선 실행이 통과했다는 이유로
    // 건너뛰면 그 질문에 답하지 않은 것이다.
    outputs.upToDateWhen { false }
}

/**
 * ★ **L3 부하 + 불변식 감사 게이트의 실체** (성공 기준 S4).
 *
 * 스테이션 20 대를 동시에 붙여 교환을 완주시킨 뒤, **이벤트 로그에서 재구성한 상태**로
 * 불변식을 전수 검사한다. `conformanceTest` 와 마찬가지로 소스셋을 나누지 않고
 * 태그로만 가른다 — 감사 시험도 `FixedClockConfig` 같은 기존 시험 지원 코드를 그대로 쓴다.
 *
 * `test` 와 `conformanceTest` 어디에도 섞이지 않는다. 세 게이트가 서로 다른 질문에 답하기
 * 때문이다: 단위는 "부품이 맞는가", 적합성은 "표준대로 대화하는가", 감사는 "동시에 밀어넣어도
 * 장부가 맞는가".
 */
val auditTest by tasks.registering(Test::class) {
    group = "verification"
    description = "스테이션 20대 동시 접속 후 불변식 감사 (S4)"

    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform {
        includeTags("audit")
    }

    systemProperty("csms.security.profile", "BASIC")

    // 감사는 "지금 코드가 동시성 아래서도 불변식을 지키는가"를 묻는다. 앞선 실행이
    // 통과했다는 이유로 건너뛰면 그 질문에 답하지 않은 것이다.
    outputs.upToDateWhen { false }

    // 감사 보고서는 시험의 산출물이다. 잡아먹지 않고 그대로 콘솔에 흘린다.
    testLogging {
        showStandardStreams = true
    }
}

/**
 * **Spring 은 여기서 멈춘다** (설계원칙 1).
 *
 * `ocpp-core` 와 `swap-domain` 은 프레임워크를 모르고, 그것을 각 모듈의
 * `checkNoFrameworkImports` / `checkNoExternalDependencies` 가 기계 검증한다.
 * 이 모듈이 그 두 모듈에 의존하는 것은 한 방향이므로 그 검증을 흔들지 않는다.
 */
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("csms.jar")
}
