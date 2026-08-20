-- OUT_TIMED_OUT 장부 (PLAN §5.3 불변식, §5.4 F2)
--
-- 제공된 배터리를 이용자가 꺼내가지 않아 교환이 반쪽으로 끝난 기록이다 (S03.FR.06):
--
--   "Situation needs to be reported, because CSMS ends up with an orphan BatteryIn
--    for which a BatteryOut is missing."
--
-- 이것만 영속하는 이유: 다른 상태는 전부 이벤트 로그(PLAN §11.1)에서 재구성 가능한 파생
-- 상태지만, **장부 불균형은 보상해야 할 대상**이라 프로세스가 죽어도 남아 있어야 한다.
-- PLAN §5.3 이 이 한 줄에만 "영속 저장 필요"를 달아 두었다.
--
-- 마이그레이션 도구를 도입하지 않는다. 테이블 하나에 Flyway/Liquibase 를 붙이는 것은
-- 과설계다 (PLAN §11.0). Spring Boot 가 기동 시 이 파일을 실행한다.

CREATE TABLE IF NOT EXISTS swap_out_timed_out (
    -- 상관키는 (stationId, requestId) 복합키다. requestId 는 스테이션 범위에서만
    -- 유일하므로 단독 PK 가 될 수 없다 (PLAN §5.3).
    station_id        VARCHAR(64)  NOT NULL,
    request_id        INT          NOT NULL,

    id_token          VARCHAR(255) NOT NULL,
    id_token_type     VARCHAR(20)  NOT NULL,

    -- 대응하는 출고가 없는 입고 배터리 수. 항상 양수다 — 그게 이 기록의 존재 이유다.
    ledger_imbalance  INT          NOT NULL,

    -- orphan 배터리의 serialNumber·soC·soH 를 그대로 보존한다 (PLAN §11.2 — 양쪽 SoC 를
    -- 버리면 나중에 과금이 스키마 변경 없이는 불가능해진다). 배터리는 세트 단위라
    -- 여러 개일 수 있어 JSON 배열 원문으로 둔다.
    orphan_batteries  CLOB         NOT NULL,

    authorized_at     TIMESTAMP    NOT NULL,
    started_at        TIMESTAMP    NOT NULL,
    timed_out_at      TIMESTAMP    NOT NULL,

    CONSTRAINT pk_swap_out_timed_out PRIMARY KEY (station_id, request_id)
);

CREATE TABLE IF NOT EXISTS ocpp_event (
    station_id   VARCHAR(64)  NOT NULL,
    seq          BIGINT       NOT NULL,
    direction    VARCHAR(16)  NOT NULL,
    action       VARCHAR(128),
    message_id   VARCHAR(255) NOT NULL,
    payload      CLOB         NOT NULL,
    occurred_at  TIMESTAMP    NOT NULL,

    CONSTRAINT pk_ocpp_event PRIMARY KEY (station_id, seq)
);
