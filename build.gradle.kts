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
     * L1 단위 게이트 — 적합성은 **여기서 빠진다** (PLAN §7.3).
     *
     * 표준 적합성 시험이 일반 단위 시험에 섞이면, 200건 넘는 초록 사이에서 `TC_S_103_CSMS`
     * 하나가 빨개져도 눈에 띄지 않는다. 게이트를 나눠 두면 "단위는 통과했는데 적합성이
     * 깨졌다"가 한눈에 읽힌다 — §7.3 이 L1/L2 를 따로 둔 이유다.
     */
    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags(ConformanceTag.VALUE)
        }
    }
}

/** 적합성 시험을 가르는 JUnit 태그. 시험 코드와 빌드가 같은 문자열을 보게 한 곳에 둔다. */
object ConformanceTag {
    const val VALUE = "conformance"
}

/**
 * ★ **L2 표준 적합성 게이트** (PLAN §7.3).
 *
 * ```
 * --gate "conformance:./gradlew conformanceTest"
 * ```
 *
 * `TC_S_102_CSMS` · `TC_S_103_CSMS` 와 실패 시나리오 F1~F6 이 여기서 돈다. 실제 시험은
 * `:csms` 에 있고 (시험 대상이 CSMS 이므로), 이 태스크는 루트에서 부를 수 있게 모아 준다.
 */
tasks.register("conformanceTest") {
    group = "verification"
    description = "공식 적합성 케이스(TC_S_102/103_CSMS)와 실패 시나리오 F1~F6 (PLAN §7.3 L2)"
    dependsOn(":csms:conformanceTest")
}
