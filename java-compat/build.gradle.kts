// Java 소비자 관점의 호환 게이트 (공개 로드맵 4단계).
//
// 이 모듈에는 main 소스가 없다. 시험만 있고, 그 시험은 **Java 로만** 쓴다.
// `ocpp-core` 를 밖에서 의존하는 사람이 Kotlin 없이 코덱·스키마 층을 부를 수 있는지를
// 컴파일과 실행으로 확인한다 — 산문 주장을 실측으로 바꾸는 자리다 (docs/LAYERS.md §4).

dependencies {
    testImplementation(project(":ocpp-core"))
}

/**
 * 이 모듈은 **Kotlin 을 한 줄도 쓰지 않는다.** 그래야 "Java 에서 된다"는 판정이 성립한다.
 *
 * 루트 빌드가 모든 서브프로젝트에 Kotlin 플러그인을 적용하므로 `.kt` 를 놔도 그냥 컴파일된다.
 * 그러면 이 게이트가 조용히 무의미해지므로, 산문 대신 `check` 에 물려 기계 검증한다.
 * (`ocpp-core:checkNoFrameworkImports` 와 같은 취지다.)
 */
val checkNoKotlinSources by tasks.registering {
    val kotlinSources = layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") }
    inputs.files(kotlinSources)

    doLast {
        val offenders = kotlinSources.files.sorted().map { it.name }
        check(offenders.isEmpty()) {
            "java-compat 은 Java 로만 쓴다 (docs/LAYERS.md §4): ${offenders.joinToString()}"
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoKotlinSources)
}
