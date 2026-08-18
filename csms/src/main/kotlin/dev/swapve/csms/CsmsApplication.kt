package dev.swapve.csms

import dev.swapve.csms.config.CsmsProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * 관제 서버(CSMS) 진입점.
 *
 * **CSMS 가 WebSocket 서버고 충전소가 클라이언트다** (Part 4 Edition 2 §3). 스테이션은 연결을
 * 항상 열어 두고 붙어 있으므로, 우리가 먼저 접속을 시도하는 코드는 어디에도 없다.
 *
 * Spring 은 이 모듈에서 멈춘다. `ocpp-core` 와 `swap-domain` 은 프레임워크를 모르고
 * (PLAN §6 설계원칙 1), 그 사실을 두 모듈의 `checkNoFrameworkImports` /
 * `checkNoExternalDependencies` 가 빌드 때마다 기계 검증한다.
 */
@SpringBootApplication
@EnableConfigurationProperties(CsmsProperties::class)
class CsmsApplication

fun main(args: Array<String>) {
    runApplication<CsmsApplication>(*args)
}
