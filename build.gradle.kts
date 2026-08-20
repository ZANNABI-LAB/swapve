plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
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
     * L1 단위 게이트 — 적합성과 부하·감사는 **여기서 빠진다**.
     *
     * 표준 적합성 시험이 일반 단위 시험에 섞이면, 200건 넘는 초록 사이에서 `TC_S_103_CSMS`
     * 하나가 빨개져도 눈에 띄지 않는다. 게이트를 나눠 두면 "단위는 통과했는데 적합성이
     * 깨졌다"가 한눈에 읽힌다 — 게이트 이 L1/L2/L3 를 따로 둔 이유다.
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

/**
 * ★ **Maven Central 배포** (공개 로드맵 6단계).
 *
 * **라이브러리 두 개만 올린다.** `csms` · `station-sim` · `sim-console` 은 애플리케이션이고,
 * `java-compat` 은 시험 전용이라 좌표를 가질 이유가 없다. 남이 의존으로 적을 수 있는 것은
 * 프레임워크를 모르는 두 모듈뿐이다 (docs/LAYERS.md).
 *
 * Sonatype 은 아직 Central Portal 용 공식 Gradle 플러그인을 내놓지 않았다. 커뮤니티 표준을
 * 쓰되, **빌드 스크립트의 의존이지 라이브러리의 런타임 의존이 아니다** — `swap-domain` 의
 * `checkNoExternalDependencies` 는 `compileClasspath` 를 보므로 영향이 없다.
 *
 * 좌표와 버전은 `gradle.properties` 에 있다. 절차는 `docs/PUBLISHING.md`.
 */
val publishedModules = setOf("ocpp-core", "swap-domain")

subprojects {
    if (name !in publishedModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral()

        // 서명은 Central 의 요구사항이다. 키가 없는 환경(로컬 리허설·CI 의 일반 빌드)에서는
        // 아래 `signing.required` 가 이를 건너뛰게 한다 — 리허설이 키 때문에 막히면 안 된다.
        signAllPublications()

        coordinates(rootProject.group.toString(), project.name, rootProject.version.toString())

        pom {
            name.set("SwapVe ${project.name}")
            description.set(
                when (project.name) {
                    "ocpp-core" ->
                        "OCPP 2.1 (OCPP-J) framing, official JSON schema validation, and session layer for the JVM. Framework-agnostic."
                    else ->
                        "OCPP 2.1 Battery Swap (Block S) domain model: swap state machine, slot model, and invariants. No I/O, no dependencies."
                },
            )
            inceptionYear.set("2026")
            url.set("https://github.com/ZANNABI-LAB/swapve")

            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("ZANNABI-LAB")
                    name.set("ZANNABI LAB")
                    url.set("https://github.com/ZANNABI-LAB")
                }
            }
            scm {
                url.set("https://github.com/ZANNABI-LAB/swapve")
                connection.set("scm:git:git://github.com/ZANNABI-LAB/swapve.git")
                developerConnection.set("scm:git:ssh://git@github.com/ZANNABI-LAB/swapve.git")
            }
        }
    }

    /**
     * ⚠️ **jar 안에 `LICENSE` 와 `NOTICE` 를 넣는다.** 리허설에서 빠져 있는 것을 발견했다.
     *
     * 두 가지 이유가 있고 둘 다 강제다:
     * ① Apache-2.0 §4(d) — NOTICE 파일이 있는 저작물을 재배포할 때 그 고지를 함께 준다.
     * ② **`ocpp-core` 의 jar 에는 OCA 공식 스키마 181개가 들어 있다** (CC BY-ND 4.0).
     *    저장소를 안 보고 좌표만으로 받은 사람은 NOTICE 를 볼 방법이 jar 안뿐이다.
     *
     * sources jar 에도 스키마가 실리므로 모든 `Jar` 태스크에 건다.
     */
    tasks.withType<Jar>().configureEach {
        metaInf {
            from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
        }
    }

    // 서명 키가 없으면 서명을 요구하지 않는다. **배포 경로는 이 완화를 타지 않는다** —
    // Central 이 서명 없는 번들을 거절하므로, 키를 잊으면 업로드 단계에서 잡힌다.
    extensions.configure<SigningExtension>("signing") {
        isRequired = providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.keyId").isPresent
    }
}

/** 적합성 시험을 가르는 JUnit 태그. 시험 코드와 빌드가 같은 문자열을 보게 한 곳에 둔다. */
object ConformanceTag {
    const val VALUE = "conformance"
}

/** 부하 + 불변식 감사를 가르는 JUnit 태그 (L3). */
object AuditTag {
    const val VALUE = "audit"
}

/**
 * ★ **L2 표준 적합성 게이트**.
 *
 * ```
 * --gate "conformance:./gradlew conformanceTest"
 * ```
 *
 * `TC_S_102_CSMS` · `TC_S_103_CSMS` · `TC_S_104_CS` 와 실패 시나리오 F1~F6 이 여기서 돈다.
 * 실제 시험은 전부 `:csms` 에 있다 — 앞의 둘은 시험 대상이 CSMS 라서이고,
 * `TC_S_104_CS` 는 반대로 **CSMS 가 시험계**라서 그렇다.
 */
tasks.register("conformanceTest") {
    group = "verification"
    description = "공식 적합성 케이스(TC_S_102/103_CSMS · TC_S_104_CS)와 실패 시나리오 F1~F6 (L2)"
    dependsOn(":csms:conformanceTest")
}

/**
 * ★ **L3 부하 + 불변식 감사 게이트** (성공 기준 S4).
 *
 * ```
 * --gate "audit:./gradlew auditTest"
 * ```
 *
 * 스테이션 20 대가 동시에 붙어 교환을 완주한 뒤, **이벤트 로그(이벤트 로그)에서 재구성한 상태**로
 * 불변식을 전수 검사한다. 실체는 `:csms` 에 있다 — 시험 대상이 CSMS 이므로.
 */
tasks.register("auditTest") {
    group = "verification"
    description = "스테이션 20대 동시 접속 후 불변식 감사 (S4, 게이트 L3)"
    dependsOn(":csms:auditTest")
}
