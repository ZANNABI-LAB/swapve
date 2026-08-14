// 스테이션 시뮬레이터 — 슬롯 · 배터리 · 장애 주입. (M6)
// 전송은 JDK 내장 java.net.http.WebSocket 을 쓴다 — 외부 의존성 0.

dependencies {
    implementation(project(":ocpp-core"))
    implementation(project(":swap-domain"))
}
