# Spring Batch 실무 문제 실습

- Java 17
- Spring Boot 3.5.16
- Spring Batch 5.x
- MariaDB 11.8.8

## 목적

Spring Batch 실무에서 실제로 겪는 성능·동시성·장애 문제를 **일부러 재현한 구현 (`before` 프로파일)** 과 **개선한 구현 (`after` 프로파일)** 으로 나란히 두고, 프로파일을 토글해
가며 **처리 시간 / CPU / IO**를 수치로 비교 학습한다.

## 실습 규칙

1. **문제 1개 = 테이블 1개.** 7가지 문제는 동일한 스키마를 가진 `member_a` ~ `member_g` 테이블에서 각각 독립적으로 재현한다. 문제끼리 데이터를 공유하지 않으므로 한 실습이 다른 실습을
   오염시키지 않는다.
2. **문제 1개 = Job 1개.** 하나의 Job에 여러 문제의 Step을 묶지 않는다.
    - `Job → 1번문제Step → 2번문제Step` (X)
    - `Job → 1번문제Step`, `Job → 2번문제Step` (O)
3. **before/after는 코드 분기가 아니라 프로파일로 토글한다.** `if (profile == before)` 같은 런타임 분기 대신 프로파일별 Bean 구성으로 나눈다. Job/Step 이름은 양쪽이
   동일해야 같은 축에서 비교할 수 있다.
4. **측정 없는 개선은 인정하지 않는다.** 각 문제마다 정의된 측정 지표를 before/after 양쪽에서 기록한다.

### 공통 테이블 스키마

`member_a` ~ `member_g` 는 아래 스키마를 공유한다. 문제마다 달라지는 것은 **데이터 분포 / 인덱스 구성 / 읽기·쓰기 방식**이지 스키마가 아니다.

```sql
CREATE TABLE member_x
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    email           VARCHAR(100) NOT NULL,
    name            VARCHAR(50)  NOT NULL,
    grade           VARCHAR(20)  NOT NULL,           -- BRONZE / SILVER / GOLD / VIP
    point           BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    referrer_id     BIGINT       NULL,               -- 자기 참조 (4번 문제 N+1 재현용)
    processed       TINYINT(1)   NOT NULL DEFAULT 0, -- process indicator (5번 문제)
    idempotency_key VARCHAR(64)  NULL,               -- 멱등키 (5·7번 문제)
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;
```

부가 테이블은 **after 개선안이 구조적으로 요구하는 경우에만** 추가한다 (2번 격리 테이블, 7번 Outbox 테이블). 그 외 문제는 주 테이블 1개로 끝낸다.

실제 DDL은 `src/main/resources/schema.sql` 이다. **보조 인덱스는 거기에 없다.** 1번의 `email` UK·`grade`·`created_at` 인덱스, 5번의 `idempotency_key` UK, 6번의 `(grade, point)`
인덱스는 존재 여부와 생성 시점 자체가 before/after의 차이이므로 스키마가 아니라 프로파일이 제어한다. 해당 `ALTER TABLE` 문은 `schema.sql` 하단 주석에 모아 두었다.

## 준비

### 1. 스키마 적용

`schema.sql` 은 외부 DB에서 자동 실행되지 않는다 (`spring.sql.init.mode` 기본값이 `embedded`). `application.properties` 에 아래를 추가한다.

```properties
spring.sql.init.mode=always
```

모든 DDL이 `IF NOT EXISTS` 라서 매 기동마다 실행되어도 안전하다.

### 2. 테스트 데이터 시딩

`seedJob` 이 `member_b` ~ `member_g` 를 채운다. **대상 테이블 하나당 한 번 실행한다.** 6개를 한 Job의 6개 Step으로 묶지 않은 이유는, 200만 건 시딩이 실패해도 다른 테이블이 영향을 받지
않아야 하기 때문이다.

```bash
SEED="--spring.batch.job.enabled=true --spring.batch.job.name=seedJob"

./gradlew bootRun --args="$SEED target=member_b"   #  10만 건 (오염 행 500건 포함)
./gradlew bootRun --args="$SEED target=member_c"   # 200만 건
./gradlew bootRun --args="$SEED target=member_d"   #  50만 건 (모든 행이 referrer_id 보유)
./gradlew bootRun --args="$SEED target=member_e"   #  30만 건
./gradlew bootRun --args="$SEED target=member_f"   # 100만 건
./gradlew bootRun --args="$SEED target=member_g"   #  10만 건
```

`member_a` 는 시딩 대상이 아니다. "비어 있는 `member_a` 에 100만 건 적재"가 1번 문제의 측정 대상이므로 `insertJob` 이 데이터를 생성하며 직접 적재한다.

Job 파라미터는 `--` **없이** 넘긴다 (`target=member_c`). `--` 를 붙인 인자는 Spring 설정으로 해석된다.

| Job 파라미터 | 기본값        | 설명            |
|--------------|---------------|-----------------|
| `target`     | (필수)        | 대상 테이블     |
| `count`      | 테이블별 규모 | 생성 건수       |
| `chunkSize`  | `5000`        | 커밋 단위       |
| `seed`       | `20260814`    | 난수 시드       |

생략한 파라미터는 **직전 실행 값이 아니라 기본값**으로 해석된다. Spring Boot는 Job에 incrementer가 있으면 이전 실행의 파라미터를 물려준 뒤 CLI 인자를 덮는데, 그러면 `count` 를
생략했을 때 이전 실행의 값이 조용히 상속되어 시딩 규모가 틀어진다. `run.id` 만 넘기는 incrementer를 따로 두어 막았다.

적재되는 데이터는 `(target, count, seed)` 의 **순수 함수**다. `id` 까지 순번으로 직접 지정하므로 같은 파라미터면 언제나 같은 테이블이 된다. before/after가 문자 그대로 동일한 입력을 받는다는
보장이 여기서 나온다. (`AUTO_INCREMENT` 에 맡기면 MariaDB 드라이버의 bulk INSERT와 InnoDB의 블록 단위 할당 때문에 `id` 에 구멍이 생겨, 4번 문제의 `referrer_id` 가
실재하지 않는 행을 가리킬 수 있다.)

대상 테이블이 비어 있지 않으면 `seedJob` 은 시작하지 않는다. 중복 시딩은 이후 모든 측정치를 무효하게 만든다. 다시 시딩하려면 `TRUNCATE TABLE member_x` 후 실행한다 (`TRUNCATE` 는
`AUTO_INCREMENT` 도 되돌리므로 같은 데이터가 재현된다).

## 실행

```bash
# before 재현
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=insertJob'

# after 개선
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after  --spring.batch.job.name=insertJob'
```

`--spring.batch.job.name` 으로 실행할 Job 하나만 지정한다. `application.properties` 의 `spring.batch.job.enabled=false` 때문에 기본 상태에서는 아무 Job도 실행되지
않으므로, 실행할 때마다 `--spring.batch.job.enabled=true` 를 함께 넘긴다. 이 값을 켠 채로 Job 이름을 생략하면 컨텍스트의 **모든** Job이 실행되니 주의한다.

## 학습 목표: 7가지 실무 문제

| # | 실무 문제                                      | 테이블     | Job          | before 증상                                | after 개선 기법                           |
|---|------------------------------------------------|------------|--------------|--------------------------------------------|-------------------------------------------|
| 1 | 대량 INSERT 성능 (인덱스 유지 비용, 청크 커밋) | `member_a` | `insertJob`  | 인덱스 랜덤 갱신 + 잦은 커밋으로 적재 지연 | 인덱스 후생성, 청크 확대, JDBC batch 쓰기 |
| 2 | skip/retry, 오류 행 격리                       | `member_b` | `skipJob`    | 오류 1건에 Step 전체 실패                  | faultTolerant + SkipListener 격리         |
| 3 | offset 페이징 함정                             | `member_c` | `pagingJob`  | 뒤 페이지로 갈수록 페이지당 시간 선형 증가 | 커서 스트리밍 / 키셋(ZeroOffset) 페이징   |
| 4 | Processor N+1 조회, 청크 사이즈                | `member_d` | `lookupJob`  | 행당 2회 조회 → 쿼리 수 폭증               | 정책 캐시 + 청크 단위 IN 일괄 조회        |
| 5 | 재시작(restart) 멱등성                         | `member_e` | `restartJob` | 실패 후 재실행 시 포인트 이중 소멸         | process indicator + 멱등키 UK             |
| 6 | 대량 UPDATE 쓰기 경로                          | `member_f` | `updateJob`  | 행별 UPDATE 반복 (bulk 미적용)             | 집합 UPDATE / `useBulkStmts`              |
| 7 | 외부 통보와 트랜잭션 경계                      | `member_g` | `outboxJob`  | 롤백 시 유령 알림, 재실행 시 중복 발송     | Outbox 패턴 + 멱등키                      |

---

## 1. 대량 INSERT 성능

| 항목   | 값                                    |
|--------|---------------------------------------|
| 테이블 | `member_a`                            |
| Job    | `insertJob` → `insertStep` (Step 1개) |
| 데이터 | 100만 건 신규 적재                    |

**시나리오** — 비어 있는 `member_a` 에 100만 건을 적재한다.

**before**

- `member_a` 에 `email` UK, `grade`, `created_at` 보조 인덱스를 **미리 생성**한 상태에서 적재 → 행마다 인덱스 랜덤 갱신
- `chunk(100)` → 1만 번 커밋
- `JpaItemWriter` 로 행별 INSERT

**after**

- PK만 둔 상태로 적재하고 **적재 완료 후 인덱스 생성**
- `chunk(5000)` 으로 커밋 횟수 200배 감소
- `JdbcBatchItemWriter` + JDBC URL `rewriteBatchedStatements=true`

**측정 지표** — 총 소요 시간, 커밋 횟수, INSERT 쿼리 왕복 횟수, 디스크 write IO

---

## 2. skip/retry, 오류 행 격리

| 항목   | 값                                                            |
|--------|---------------------------------------------------------------|
| 테이블 | `member_b` (+ after 전용 격리 테이블 `member_b_error`)        |
| Job    | `skipJob` → `skipStep` (Step 1개)                             |
| 데이터 | 10만 건 중 오염 행 500건 (`email` 형식 오류, `point` 음수 등) |

**시나리오** — `member_b` 를 읽어 검증·가공하는데 일부 행이 오염되어 있다.

**before**

- 오류 처리 없음 → 첫 오염 행에서 예외 발생, Step 전체 `FAILED`
- 이미 커밋된 청크와 실패 지점 사이의 처리 결과가 불명확

**after**

- `.faultTolerant().skip(ValidationException.class).skipLimit(1000)`
- 일시 오류 (락 타임아웃 등)는 `.retry(...).retryLimit(3)` 로 구분
- `SkipListener` 가 스킵된 행을 `member_b_error` 에 원인과 함께 격리 → Step은 `COMPLETED`, 오염 행은 사후 추적 가능

**측정 지표** — Step 종료 상태, 처리/스킵/재시도 건수, 격리 테이블 적재 건수

---

## 3. offset 페이징 함정

| 항목   | 값                                    |
|--------|---------------------------------------|
| 테이블 | `member_c`                            |
| Job    | `pagingJob` → `pagingStep` (Step 1개) |
| 데이터 | 200만 건                              |

**시나리오** — `member_c` 전체를 순회하며 읽는다.

**before**

- `JdbcPagingItemReader` 의 offset 방식 (`LIMIT 1000 OFFSET n`)
- 뒤 페이지일수록 DB가 앞 레코드를 버리는 비용이 커져 **페이지당 시간이 선형 증가**

**after**

- 키셋 페이징: `WHERE id > :lastId ORDER BY id ASC LIMIT 1000` (offset 0 고정)
- 또는 `JdbcCursorItemReader` / `fetchSize` 조정으로 커서 스트리밍

**측정 지표** — 페이지 번호별 소요 시간 그래프 (선형 증가 vs 평탄), 총 소요 시간, DB CPU

---

## 4. Processor N+1 조회, 청크 사이즈

| 항목   | 값                                     |
|--------|----------------------------------------|
| 테이블 | `member_d` (`referrer_id` 자기 참조)   |
| Job    | `lookupJob` → `lookupStep` (Step 1개)  |
| 데이터 | 50만 건, 각 행이 `referrer_id` 를 가짐 |

**시나리오** — 각 회원의 추천인 정보와 등급 정책을 참조해 가공한다. 별도 정책 테이블 없이 `member_d` 자기 참조만으로 N+1을 재현한다.

**before**

- `ItemProcessor` 가 행마다 추천인 조회 1회 + 추천인 등급 확인 1회 = **행당 2회 SELECT**
- 50만 건 → 100만 번의 쿼리 왕복

**after**

- 등급 정책은 Step 시작 시 1회 로딩해 캐시
- 청크의 `referrer_id` 를 모아 `WHERE id IN (...)` **청크당 1회 조회**
- 청크 사이즈를 조정하며 쿼리 수와 메모리의 트레이드오프 확인

**측정 지표** — 총 SELECT 실행 횟수, 총 소요 시간, 청크 사이즈별 비교표

---

## 5. 재시작 (restart) 멱등성

| 항목   | 값                                      |
|--------|-----------------------------------------|
| 테이블 | `member_e`                              |
| Job    | `restartJob` → `restartStep` (Step 1개) |
| 데이터 | 30만 건, 중간 지점에서 강제 실패 유발   |

**시나리오** — 포인트를 차감하는 배치가 중간에 실패한 뒤 같은 파라미터로 재실행된다.

**before**

- 처리 여부를 기록하지 않고 조건만으로 대상을 조회 → 재실행 시 **이미 차감된 행을 다시 차감**
- 실행 횟수에 따라 결과가 달라짐 (비멱등)

**after**

- **process indicator**: `processed = 0` 인 행만 읽고, 같은 트랜잭션에서 `processed = 1` 로 마킹
- `idempotency_key` 에 UK를 걸어 중복 처리 시도를 DB 레벨에서 차단
- 재실행해도 최종 포인트 총합이 동일

**측정 지표** — 1회 실행 후 포인트 총합 vs 실패 후 재실행 후 포인트 총합 (동일해야 함), 중복 처리 건수

---

## 6. 대량 UPDATE 쓰기 경로

| 항목   | 값                                    |
|--------|---------------------------------------|
| 테이블 | `member_f`                            |
| Job    | `updateJob` → `updateStep` (Step 1개) |
| 데이터 | 100만 건 전체 등급 재계산             |

**시나리오** — 조건에 따라 `member_f` 의 `grade` / `updated_at` 을 일괄 갱신한다.

**before**

- 읽은 행마다 `UPDATE member_f SET ... WHERE id = ?` 개별 실행
- JDBC batch가 실제로 묶이지 않아 왕복 횟수가 행 수와 동일

**after**

- 조건 기반 **집합 UPDATE** (`UPDATE member_f SET grade = ... WHERE grade = ... AND point BETWEEN ...`)
- 행 단위가 불가피한 경우 `JdbcBatchItemWriter` + `rewriteBatchedStatements=true` / `useBulkStmts=true`

**측정 지표** — UPDATE 문 실행 횟수, 네트워크 왕복 횟수, 총 소요 시간, 락 유지 시간

---

## 7. 외부 통보와 트랜잭션 경계

| 항목   | 값                                          |
|--------|---------------------------------------------|
| 테이블 | `member_g` (+ after 전용 `member_g_outbox`) |
| Job    | `outboxJob` → `outboxStep` (Step 1개)       |
| 데이터 | 10만 건, 특정 청크에서 커밋 실패 유발       |

**시나리오** — 회원 상태를 변경하고 외부 알림을 발송한다. 알림 발송은 롤백되지 않는다.

**before**

- `ItemWriter` 안에서 DB 쓰기와 외부 알림 발송을 함께 수행
- 커밋 실패 시 DB는 롤백되지만 알림은 이미 나감 → **유령 알림**
- 재실행 시 같은 대상에게 **중복 발송**

**after**

- Step 트랜잭션 안에서는 `member_g_outbox` 에 발송 요청만 기록 (DB 커밋과 원자적)
- 커밋 이후 별도 릴레이가 Outbox를 읽어 실제 발송
- `idempotency_key` UK로 재실행 시 중복 발송 차단

**측정 지표** — 롤백 발생 시 실제 발송 건수 (0이어야 함), 재실행 후 총 발송 건수, Outbox 적재 건수 대비 발송 건수

---

## 비교 기록 템플릿

각 문제를 실습할 때 아래 표를 채운다.

| 문제 | Job         | 프로파일 | 총 소요 시간 | 쿼리/커밋 횟수 | CPU | IO | 비고 |
|------|-------------|----------|--------------|----------------|-----|----|------|
| 1    | `insertJob` | before   |              |                |     |    |      |
| 1    | `insertJob` | after    |              |                |     |    |      |
