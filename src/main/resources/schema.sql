-- =====================================================================
-- Spring Batch 실무 문제 실습 스키마 (MariaDB 11.8)
--
-- 규칙
--  1. 문제 1개 = 테이블 1개. member_a ~ member_g 는 동일한 공통 스키마를 공유한다.
--  2. 공통 테이블은 PK 만 가진 상태로 생성한다.
--     보조 인덱스 / UK 는 before·after 의 "차이" 그 자체이므로 여기서 만들지 않고
--     프로파일별 Bean 또는 Job 이 제어한다. (파일 하단 주석 참고)
--  3. 부가 테이블은 after 개선안이 구조적으로 요구하는 2개만 둔다.
--     - member_b_error  : 2번 오류 행 격리
--     - member_g_outbox : 7번 Outbox 패턴
--  4. 모든 DDL 은 IF NOT EXISTS. spring.sql.init 이 매 기동마다 실행해도 안전해야 한다.
--     데이터를 비우고 다시 실습하려면 파일 하단의 리셋 스니펫을 수동 실행한다.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. 대량 INSERT 성능 : insertJob / 100만 건 신규 적재
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_a
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 2. skip/retry, 오류 행 격리 : skipJob / 10만 건 중 오염 행 500건
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_b
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- after 전용. SkipListener 가 스킵된 행을 원인과 함께 격리한다.
-- 격리 자체가 목적이므로 원본 행을 FK 로 묶지 않는다 (원본이 삭제/재적재되어도 기록은 남아야 한다).
CREATE TABLE IF NOT EXISTS member_b_error
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    member_id      BIGINT        NULL,           -- read 단계 스킵이면 NULL 일 수 있다
    phase          VARCHAR(20)   NOT NULL,       -- READ / PROCESS / WRITE
    raw_item       VARCHAR(1000) NULL,           -- 스킵된 item 의 문자열 표현
    exception_type VARCHAR(255)  NOT NULL,
    message        VARCHAR(1000) NULL,
    step_execution_id BIGINT     NULL,
    skipped_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 3. offset 페이징 함정 : pagingJob / 200만 건
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_c
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 4. Processor N+1 조회 : lookupJob / 50만 건, referrer_id 자기 참조
-- ---------------------------------------------------------------------
-- referrer_id 는 자기 참조이지만 FK 를 걸지 않는다.
-- 대량 적재 시 FK 체크 비용이 1번 문제의 측정치를 오염시키고,
-- N+1 재현은 조회 횟수의 문제이지 참조 무결성의 문제가 아니다.
CREATE TABLE IF NOT EXISTS member_d
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 5. 재시작 멱등성 : restartJob / 30만 건
-- ---------------------------------------------------------------------
-- processed, idempotency_key 컬럼은 공통 스키마에 이미 있다.
-- before 는 이 컬럼을 쓰지 않고 조건 조회만 한다 (비멱등 재현).
-- after 의 idempotency_key UK 는 개선 기법이므로 여기서 만들지 않는다.
CREATE TABLE IF NOT EXISTS member_e
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 6. 대량 UPDATE 쓰기 경로 : updateJob / 100만 건 등급 재계산
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_f
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;


-- ---------------------------------------------------------------------
-- 7. 외부 통보와 트랜잭션 경계 : outboxJob / 10만 건
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_g
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,
    processed       TINYINT(1)   NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- after 전용. Step 트랜잭션 안에서는 "발송 요청"만 여기에 기록하고,
-- 커밋 이후 별도 릴레이가 읽어서 실제 발송한다.
-- uk_member_g_outbox_key : 재실행 시 중복 발송을 DB 레벨에서 차단하는 것이
--                          Outbox 패턴의 핵심이므로 이 UK 는 테이블과 함께 만든다.
-- idx_member_g_outbox_poll : 릴레이가 미발송 건을 순서대로 폴링하는 경로.
CREATE TABLE IF NOT EXISTS member_g_outbox
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    member_id       BIGINT        NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,               -- 예: MEMBER_STATUS_CHANGED
    payload         VARCHAR(2000) NOT NULL,
    idempotency_key VARCHAR(64)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING / SENT / FAILED
    retry_count     INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000) NULL,
    created_at      DATETIME(6)   NOT NULL,
    sent_at         DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_g_outbox_key (idempotency_key),
    KEY idx_member_g_outbox_poll (status, id)
) ENGINE = InnoDB;


-- =====================================================================
-- 여기서 만들지 않는 인덱스 (= before/after 비교 대상)
--
--  1번 member_a : before 는 적재 전에 아래를 미리 만든 상태로 시작하고,
--                 after 는 적재 완료 후에 만든다. 생성 시점이 곧 측정 대상이다.
--      ALTER TABLE member_a ADD UNIQUE KEY uk_member_a_email (email);
--      ALTER TABLE member_a ADD KEY idx_member_a_grade (grade);
--      ALTER TABLE member_a ADD KEY idx_member_a_created_at (created_at);
--
--  5번 member_e : after 개선 기법. before 에 걸면 비멱등이 재현되지 않는다.
--      ALTER TABLE member_e ADD UNIQUE KEY uk_member_e_idem (idempotency_key);
--
--  6번 member_f : after 의 집합 UPDATE 조건 경로. before 는 PK 단건 UPDATE 라 무관.
--      ALTER TABLE member_f ADD KEY idx_member_f_grade_point (grade, point);
--
-- 실습 리셋 (수동 실행. 매 기동 시 데이터가 날아가면 안 되므로 이 파일에 두지 않는다)
--      TRUNCATE TABLE member_a; ... TRUNCATE TABLE member_g;
--      TRUNCATE TABLE member_b_error;
--      TRUNCATE TABLE member_g_outbox;
--      ALTER TABLE member_a DROP INDEX uk_member_a_email;  -- 등 추가 인덱스 원복
-- =====================================================================
