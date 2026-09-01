plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka) apply false
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
     * 깨졌다"가 한눈에 읽힌다 — 게이트를 L1·L2·L3 로 나눠 둔 이유다.
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

    /**
     * ★ **javadoc jar 를 Dokka 로 채운다.**
     *
     * 플러그인 기본값은 빈 jar 다 — Central 은 존재 여부만 검사하므로 그대로도 통과한다.
     * 그러나 좌표로 받은 소비자가 문서를 보는 경로는 IDE 가 붙여 주는 sources jar 와 이
     * javadoc 뿐이고, 웹에서 읽을 방법은 이것 하나다. 이 라이브러리의 값어치가 대부분
     * KDoc 에 들어 있으므로 빈 jar 로 내보내면 그 값어치가 닿지 않는다.
     */
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        configure(
            com.vanniktech.maven.publish.KotlinJvm(
                javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                sourcesJar = true,
            ),
        )

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
                        "OCPP 2.1 (OCPP-J) framing, official JSON schema validation, and a session layer for the JVM. " +
                            "Framework-agnostic. Payloads are Jackson JsonNode - there are no generated message DTOs. " +
                            "Callable from Java throughout: the codec and schema layers directly, the session layer " +
                            "through OcppSessionsAsync, which takes an Executor and returns CompletableFutures."
                    else ->
                        "OCPP 2.1 Battery Swap (Block S) domain model: swap state machine, slot model, and invariants. " +
                            "No I/O, no dependencies. Written for Kotlin consumers - the identifiers are value classes, " +
                            "which Java sees mangled."
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
    val notice = { spec: CopySpec ->
        spec.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
        Unit
    }
    tasks.withType<Jar>().configureEach { metaInf(notice) }
    // Dokka 가 만드는 javadoc jar 는 위 `withType<Jar>` 에 걸리지 않는다 — 플러그인이 자기
    // 타입으로 등록하기 때문이다. 배포되는 아티팩트이므로 따로 건다.
    tasks.withType<com.vanniktech.maven.publish.tasks.JavadocJar>().configureEach { metaInf(notice) }

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
 * 스테이션 20 대가 동시에 붙어 교환을 완주한 뒤, **이벤트 로그에서 재구성한 상태**로
 * 불변식을 전수 검사한다. 실체는 `:csms` 에 있다 — 시험 대상이 CSMS 이므로.
 */
tasks.register("auditTest") {
    group = "verification"
    description = "스테이션 20대 동시 접속 후 불변식 감사 (S4, 게이트 L3)"
    dependsOn(":csms:auditTest")
}

/**
 * ★★ **받아서 바로 돌릴 수 있는 배포물** — Maven Central 과는 다른 물건이다.
 *
 * ```
 * ./gradlew releaseBundle     # build/distributions/swapve-<version>.zip
 * ```
 *
 * Central 에 올라가는 것은 **라이브러리 둘**(`ocpp-core`·`swap-domain`)이고, 그것은
 * 코드를 쓰는 사람을 위한 것이다. 이 zip 은 **도구를 쓰는 사람**을 위한 것이다 —
 * CSMS 하나와 스테이션 시뮬레이터, 그리고 그 시뮬레이터를 브라우저에서 조종하는 콘솔.
 * 둘은 청중이 다르므로 `publishedModules` 는 그대로 둔다.
 *
 * ### 무엇이 들어가는가
 *
 * - `csms/` — Spring Boot 실행 가능 jar 하나
 * - `station-sim/` · `sim-console/` — `installDist` 산출물 그대로(`bin/` + `lib/`)
 * - `README.md` · `LICENSE` · `NOTICE` · `docs/`
 *
 * 스펙 PDF 도 `schemas/` 도 넣지 않는다. 전자는 애초에 저장소에 없고, 후자는
 * OCA 의 CC BY-ND 저작물이라 재배포 조건이 우리 라이선스와 다르다([NOTICE]).
 */
val releaseBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "콘솔·시뮬레이터·CSMS 를 한 벌로 묶은 배포물 (Central 과 별개)"

    archiveBaseName.set("swapve")
    archiveVersion.set(version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // 압축을 풀면 곧바로 버전이 보이는 디렉토리 하나가 나온다.
    into("swapve-$version") {
        into("csms") {
            from(project(":csms").tasks.named("bootJar"))
        }
        into("station-sim") {
            from(project(":station-sim").tasks.named("installDist"))
        }
        into("sim-console") {
            from(project(":sim-console").tasks.named("installDist"))
        }
        from(layout.projectDirectory.file("README.md"))
        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
        from(layout.projectDirectory.file("CHANGELOG.md"))
        into("docs") {
            from(layout.projectDirectory.dir("docs")) {
                include("*.md")
                include("assets/**")
            }
        }
    }
}

/**
 * ★ **두 벌의 README 가 조용히 갈리지 않게 대조한다** (`README.md` ↔ `README.ko.md`).
 *
 * ```
 * ./gradlew checkReadmeTranslationsInSync     # `build` 에 이미 물려 있다
 * ```
 *
 * `docs/` 는 영문 단일본이지만 README 만은 두 벌 전문으로 유지한다. 그 값을 치르는 대신
 * **갈리면 알아채야 한다** — 실제로 갈렸던 자리가 둘 있다. 한쪽만 고친 문장(`0.3.0` 에서
 * 세션 층이 Java 에서 호출 가능해졌는데 한글판 문구가 남아 있었다)과, 한글판 문서 표에서만
 * 빠져 있던 `docs/VIRTUAL-STATION.md` 링크다. 사람의 눈으로는 두 번 다 놓쳤다.
 *
 * 대조하는 것은 **번역할 수 없는 것 셋**뿐이다 — 절의 개수, Maven 좌표, 링크 대상.
 * 산문은 언어마다 다를 수밖에 없으므로 건드리지 않는다. 절 **제목**도 마찬가지다.
 *
 * 새 게이트를 만들지 않는다 — 루트의 `check` 에 매달아 두면 L1 `build` 가 이미 도는
 * 자리에서 돈다 (`java-compat:checkNoKotlinSources` 와 같은 취지다).
 */
val readmeTranslations = listOf("README.md", "README.ko.md")

val checkReadmeTranslationsInSync by tasks.registering {
    group = "verification"
    description = "README.md 와 README.ko.md 의 절 개수 · Maven 좌표 · 링크 대상을 대조한다"

    val readmes = readmeTranslations.map { layout.projectDirectory.file(it) }
    val expectedVersion = version.toString()
    val projectDir = layout.projectDirectory.asFile

    inputs.files(readmes)

    doLast {
        val sections = mutableMapOf<String, List<String>>()
        val coordinates = mutableMapOf<String, Set<String>>()
        val links = mutableMapOf<String, Set<String>>()

        val coordinatePattern = Regex("""io\.github\.zannabi-lab:[\w.-]+:[\w.-]+""")
        val linkPattern = Regex("""]\(([^)\s]+)\)""")

        readmes.forEach { readme ->
            val file = readme.asFile
            check(file.isFile) { "${file.name} 이 없다 — 파일을 옮겼으면 이 검사도 함께 고친다" }
            val text = file.readText()

            sections[file.name] = text.lines().filter { it.startsWith("## ") }
            coordinates[file.name] = coordinatePattern.findAll(text).map { it.value }.toSet()

            // 두 파일은 서로를 가리키므로 그 한 쌍만 빼고 대조한다.
            links[file.name] = linkPattern.findAll(text)
                .map { it.groupValues[1] }
                .filterNot { it in readmeTranslations }
                .toSet()
        }

        val (english, korean) = readmeTranslations

        // 절이 하나도 안 잡히면 이 검사는 아무것도 지키지 않는다 — 훑은 수를 함께 본다.
        check(sections.getValue(english).isNotEmpty()) {
            "README.md 에서 절(`## `)을 하나도 찾지 못했다 — 형식이 바뀌었으면 이 검사도 함께 고친다"
        }

        val problems = buildList {
            val enSections = sections.getValue(english)
            val koSections = sections.getValue(korean)
            if (enSections.size != koSections.size) {
                add(
                    "절의 개수가 다르다 — $english ${enSections.size}개 · $korean ${koSections.size}개\n" +
                        "  $english: ${enSections.joinToString(" · ") { it.removePrefix("## ") }}\n" +
                        "  $korean: ${koSections.joinToString(" · ") { it.removePrefix("## ") }}",
                )
            }

            if (coordinates.getValue(english) != coordinates.getValue(korean)) {
                add(
                    "Maven 좌표가 다르다 — $english ${coordinates.getValue(english).sorted()} · " +
                        "$korean ${coordinates.getValue(korean).sorted()}",
                )
            }

            val staleCoordinates = coordinates.getValue(english).filterNot { it.endsWith(":$expectedVersion") }
            if (staleCoordinates.isNotEmpty()) {
                add("좌표의 버전이 $expectedVersion 이 아니다: ${staleCoordinates.sorted()}")
            }

            val onlyInEnglish = links.getValue(english) - links.getValue(korean)
            val onlyInKorean = links.getValue(korean) - links.getValue(english)
            if (onlyInEnglish.isNotEmpty() || onlyInKorean.isNotEmpty()) {
                add(
                    "링크 대상이 다르다 — $english 에만 ${onlyInEnglish.sorted()} · " +
                        "$korean 에만 ${onlyInKorean.sorted()}",
                )
            }

            // 저장소 안을 가리키는 링크는 실제로 그 파일이 있어야 한다. 절 앵커(`#…`)는
            // 파일 경로만 떼어 확인한다 — 앵커 자체까지 보려면 헤더를 파싱해야 하고,
            // 그것은 이 검사가 지키려는 것과 다른 일이다.
            val brokenLinks = links.values.flatten().toSortedSet()
                .filterNot { it.startsWith("http") || it.startsWith("#") }
                .filterNot { projectDir.resolve(it.substringBefore('#')).exists() }
            if (brokenLinks.isNotEmpty()) {
                add("저장소 안에 없는 파일을 가리킨다: $brokenLinks")
            }
        }

        check(problems.isEmpty()) {
            "README 두 벌이 갈렸다:\n" + problems.joinToString("\n")
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "루트에 있는 파일들의 검사 — README 두 벌 대조가 여기 매달린다"
    dependsOn(checkReadmeTranslationsInSync)
}

tasks.register("build") {
    group = "build"
    description = "루트 `check` 를 `./gradlew build` 가 함께 돌게 한다 (게이트를 늘리지 않는다)"
    dependsOn("check")
}
