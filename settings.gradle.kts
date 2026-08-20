rootProject.name = "swapve"

include(
    "ocpp-core",
    "swap-domain",
    "csms",
    "station-sim",
    "sim-console",
    // Java 소비자 관점의 호환 게이트. main 소스가 없는 시험 전용 모듈이다 (공개 로드맵 4단계)
    "java-compat",
)
