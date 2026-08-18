// 관제 서버 — OCPP-J WebSocket 엔드포인트 · S01 Authorize · 부팅/하트비트. (M5)

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":ocpp-core"))
    implementation(project(":swap-domain"))
    implementation(libs.spring.boot.starter.websocket)
    // OUT_TIMED_OUT 장부는 영속이 필요하다 (PLAN §5.3 불변식) — 인메모리로 끝내면 장부
    // 불균형이 재시작마다 사라진다. JdbcTemplate 하나로 끝내고 JPA·마이그레이션 도구는
    // 넣지 않는다. 스키마는 schema.sql 한 장이다
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.h2)
    // CsmsProperties 의 생성자 바인딩이 Kotlin 주 생성자와 기본값을 읽는 데 쓴다
    implementation(libs.kotlin.reflect)
    // @ConfigurationProperties 메타데이터. 없어도 동작하지만 IDE 자동완성이 생긴다
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    // 성공 기준 S1 — 시뮬레이터와 CSMS 를 한 테스트 안에서 **실제 소켓으로** 붙인다 (PLAN §2).
    // 시험 전용 의존이다. main 의 의존 방향은 그대로 csms → (ocpp-core, swap-domain) 이고,
    // station-sim 은 Spring 을 여전히 모른다.
    testImplementation(project(":station-sim"))
}

/**
 * 시험용 H2 는 저장소 루트가 아니라 `build/` 안에 둔다.
 *
 * 운영 설정(`application.yml`)은 `./data/swapve` 를 가리킨다 — 장부는 파일에 남아야 하고
 * (PLAN §5.3), 그건 시험에서도 마찬가지다. 다만 시험이 만든 파일까지 작업 디렉토리에
 * 쌓일 이유는 없으므로 시스템 프로퍼티로 덮는다. **인메모리로 바꾸지 않는다** — 그러면
 * "재시작 후에도 남는가"를 시험이 확인할 수 없다.
 */
tasks.withType<Test>().configureEach {
    systemProperty(
        "spring.datasource.url",
        "jdbc:h2:file:${layout.buildDirectory.get()}/test-h2/swapve;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1",
    )
}

/**
 * ★ **L2 표준 적합성 게이트의 실체** (PLAN §7.3).
 *
 * 시험 대상(System under test)이 CSMS 인 Part 6 케이스가 여기서 돈다. 시험계(Test System)
 * 역할은 `station-sim` 이 맡는다 — 시뮬레이터가 스펙 시퀀스를 그대로 연기하고, 우리가
 * 만든 CSMS 가 시험받는다.
 *
 * `test` 태스크와 **같은 소스셋을 공유하되 태그로만 갈린다.** 소스셋을 나누면 시험 지원
 * 코드(`SwapScenario`, `FixedClockConfig` …)를 복제하거나 또 다른 공유 소스셋을 만들어야
 * 하는데, 그건 게이트를 나누려다 구조를 늘리는 일이다.
 */
val conformanceTest by tasks.registering(Test::class) {
    group = "verification"
    description = "TC_S_102_CSMS · TC_S_103_CSMS 와 실패 시나리오 F1~F6 (PLAN §2 S2·S3)"

    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    useJUnitPlatform {
        includeTags("conformance")
    }

    // 적합성은 "지금 코드가 표준을 지키는가"를 묻는다. 앞선 실행이 통과했다는 이유로
    // 건너뛰면 그 질문에 답하지 않은 것이다.
    outputs.upToDateWhen { false }
}

/**
 * **Spring 은 여기서 멈춘다** (PLAN §6 설계원칙 1).
 *
 * `ocpp-core` 와 `swap-domain` 은 프레임워크를 모르고, 그것을 각 모듈의
 * `checkNoFrameworkImports` / `checkNoExternalDependencies` 가 기계 검증한다.
 * 이 모듈이 그 두 모듈에 의존하는 것은 한 방향이므로 그 검증을 흔들지 않는다.
 */
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("csms.jar")
}
