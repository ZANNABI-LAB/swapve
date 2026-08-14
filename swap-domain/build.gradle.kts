// Battery Swap 도메인 · 교환 상태머신 · 슬롯 모델. I/O 없음. (M3)

/**
 * 이 모듈은 Kotlin 표준 라이브러리 밖으로 나가지 않는다 (PLAN §6 설계원칙 1).
 *
 * 프레임워크도 JSON 라이브러리도 모르는 상태를 유지해야 테스트가 I/O 없이 돌고,
 * 전송 계층을 나중에 교체할 수 있다. 산문으로 적어두면 지켜졌는지 알 수 없으므로
 * `check` 에 물려 기계 검증한다.
 *
 * `kotlin-stdlib` 은 Kotlin 플러그인이 자동으로 붙이며, 그 추이 의존성이
 * `org.jetbrains:annotations` 다. 둘만 허용한다.
 */
val allowedCompileDependencies = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
)

val checkNoExternalDependencies by tasks.registering {
    val compileClasspath = configurations.named("compileClasspath")
    val allowed = allowedCompileDependencies

    doLast {
        val offenders = compileClasspath.get()
            .resolvedConfiguration
            .resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
            .filterNot { it in allowed }
            .distinct()
            .sorted()

        check(offenders.isEmpty()) {
            "swap-domain 은 외부 의존성을 가질 수 없다 (PLAN §6): ${offenders.joinToString()}"
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoExternalDependencies)
}
