// OCPP-J 프레이밍 · 공식 JSON Schema 검증 · 세션 계층. 프레임워크를 모른다.

dependencies {
    api(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    implementation(libs.tsid.creator)
}

/**
 * 공식 스키마 181개를 클래스패스 `ocpp/schemas/` 로 옮긴다.
 *
 * 원문을 수정하지 않고 그대로 복사한다 — CC BY-ND 4.0 (개작 금지). NOTICE 참조.
 * 디렉토리 목록은 jar 안에서 열거할 수 없으므로 `_index.txt` 를 함께 생성한다.
 */
val schemaSourceDir = rootProject.layout.projectDirectory.dir("schemas")
val schemaOutputDir = layout.buildDirectory.dir("generated-resources/schemas")

val syncOcppSchemas by tasks.registering(Sync::class) {
    from(schemaSourceDir) {
        include("*.json")
        into("ocpp/schemas")
    }
    into(schemaOutputDir)

    doLast {
        val target = schemaOutputDir.get().dir("ocpp/schemas").asFile
        val names = target.listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json") }
            .sorted()
        target.resolve("_index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(syncOcppSchemas)
}
