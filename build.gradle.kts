plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    dependencies {
        "testImplementation"(rootProject.libs.kotlin.test)
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(17)
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    /**
     * L1 단위 게이트 — 적합성과 부하·감사는 **여기서 빠진다** (PLAN §7.3).
     *
     * 표준 적합성 시험이 일반 단위 시험에 섞이면, 200건 넘는 초록 사이에서 `TC_S_103_CSMS`
     * 하나가 빨개져도 눈에 띄지 않는다. 게이트를 나눠 두면 "단위는 통과했는데 적합성이
     * 깨졌다"가 한눈에 읽힌다 — §7.3 이 L1/L2/L3 를 따로 둔 이유다.
     *
     * L3(부하 + 감사)도 같은 이유로 뺀다. 그쪽은 초 단위가 아니라 **20 대 동시 접속**을
     * 세우고 도는 시험이라, 단위 게이트에 섞이면 L1 의 "수 초" 성질이 사라진다.
     */
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags(ConformanceTag.VALUE, AuditTag.VALUE)
        }
    }
}

/** 적합성 시험을 가르는 JUnit 태그. 시험 코드와 빌드가 같은 문자열을 보게 한 곳에 둔다. */
object ConformanceTag {
    const val VALUE = "conformance"
}

/** 부하 + 불변식 감사를 가르는 JUnit 태그 (PLAN §7.3 L3). */
object AuditTag {
    const val VALUE = "audit"
}

/**
 * ★ **L2 표준 적합성 게이트** (PLAN §7.3).
 *
 * ```
 * --gate "conformance:./gradlew conformanceTest"
 * ```
 *
 * `TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS` 와 실패 시나리오 F1~F6 이 여기서 돈다.
 * 실제 시험은 전부 `:csms` 에 있다 — 앞의 둘은 시험 대상이 CSMS 라서이고,
 * `TC_S_104_CS` 는 반대로 **CSMS 가 시험계**라서 그렇다 (PLAN §7.2).
 */
tasks.register("conformanceTest") {
    group = "verification"
    description = "공식 적합성 케이스(TC_S_102/103_CSMS · TC_S_104_CS)와 실패 시나리오 F1~F6 (PLAN §7.3 L2)"
    dependsOn(":csms:conformanceTest")
}

/**
 * ★ **L3 부하 + 불변식 감사 게이트** (PLAN §7.3, 성공 기준 S4).
 *
 * ```
 * --gate "audit:./gradlew auditTest"
 * ```
 *
 * 스테이션 20 대가 동시에 붙어 교환을 완주한 뒤, **이벤트 로그(§11.1)에서 재구성한 상태**로
 * 불변식을 전수 검사한다. 실체는 `:csms` 에 있다 — 시험 대상이 CSMS 이므로.
 */
tasks.register("auditTest") {
    group = "verification"
    description = "스테이션 20대 동시 접속 후 불변식 감사 (PLAN §2 S4, §7.3 L3)"
    dependsOn(":csms:auditTest")
}
