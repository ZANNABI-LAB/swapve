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
 * **Spring 은 여기서 멈춘다** (PLAN §6 설계원칙 1).
 *
 * `ocpp-core` 와 `swap-domain` 은 프레임워크를 모르고, 그것을 각 모듈의
 * `checkNoFrameworkImports` / `checkNoExternalDependencies` 가 기계 검증한다.
 * 이 모듈이 그 두 모듈에 의존하는 것은 한 방향이므로 그 검증을 흔들지 않는다.
 */
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("csms.jar")
}
