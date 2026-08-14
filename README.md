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

실제 DDL은 `src/main/resources/schema.sql` 이다. **보조 인덱스는 거기에 없다.** 1번의 `email` UK·`grade`·`created_at` 인덱스, 5번의
`idempotency_key` UK, 6번의 `(grade, point)`
인덱스는 존재 여부와 생성 시점 자체가 before/after의 차이이므로 스키마가 아니라 프로파일이 제어한다. 해당 `ALTER TABLE` 문은 `schema.sql` 하단 주석에 모아 두었다.

## 준비

### 1. 스키마 적용

`schema.sql` 은 외부 DB에서 자동 실행되지 않는다 (`spring.sql.init.mode` 기본값이 `embedded`). `application.properties` 에 아래를 추가한다.

```properties
spring.sql.init.mode=always
```

모든 DDL이 `IF NOT EXISTS` 라서 매 기동마다 실행되어도 안전하다.

### 2. 테스트 데이터 시딩

`seedJob` 이 `member_b` ~ `member_g` 를 채운다. **대상 테이블 하나당 한 번 실행한다.** 6개를 한 Job의 6개 Step으로 묶지 않은 이유는, 200만 건 시딩이 실패해도 다른
테이블이 영향을 받지 않아야 하기 때문이다.

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

| Job 파라미터 | 기본값        | 설명        |
|--------------|---------------|-------------|
| `target`     | (필수)        | 대상 테이블 |
| `count`      | 테이블별 규모 | 생성 건수   |
| `chunkSize`  | `5000`        | 커밋 단위   |
| `seed`       | `20260814`    | 난수 시드   |

생략한 파라미터는 **직전 실행 값이 아니라 기본값**으로 해석된다. Spring Boot는 Job에 incrementer가 있으면 이전 실행의 파라미터를 물려준 뒤 CLI 인자를 덮는데, 그러면 `count` 를
생략했을 때 이전 실행의 값이 조용히 상속되어 시딩 규모가 틀어진다. `run.id` 만 넘기는 incrementer를 따로 두어 막았다.

적재되는 데이터는 `(target, count, seed)` 의 **순수 함수**다. `id` 까지 순번으로 직접 지정하므로 같은 파라미터면 언제나 같은 테이블이 된다. before/after가 문자 그대로 동일한
입력을 받는다는 보장이 여기서 나온다. (`AUTO_INCREMENT` 에 맡기면 MariaDB 드라이버의 bulk INSERT와 InnoDB의 블록 단위 할당 때문에 `id` 에 구멍이 생겨, 4번 문제의
`referrer_id` 가 실재하지 않는 행을 가리킬 수 있다.)

대상 테이블이 비어 있지 않으면 `seedJob` 은 시작하지 않는다. 중복 시딩은 이후 모든 측정치를 무효하게 만든다. 다시 시딩하려면 `TRUNCATE TABLE member_x` 후 실행한다
(`TRUNCATE` 는
`AUTO_INCREMENT` 도 되돌리므로 같은 데이터가 재현된다).

## 실행

```bash
# before 재현
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=insertJob'

# after 개선
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after  --spring.batch.job.name=insertJob'
```

`--spring.batch.job.name` 으로 실행할 Job 하나만 지정한다. `application.properties` 의 `spring.batch.job.enabled=false` 때문에 기본 상태에서는
아무 Job도 실행되지 않으므로, 실행할 때마다 `--spring.batch.job.enabled=true` 를 함께 넘긴다. 이 값을 켠 채로 Job 이름을 생략하면 컨텍스트의 **모든** Job이 실행되니
주의한다.

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

**시나리오** — `member_b` 를 읽어 검증(이메일 형식 / 음수 포인트 / 빈 이름)하고, 통과한 행을 `processed = 1` 로 표시한다. 오염 행은 200번째마다 있고 원인은 행당 하나뿐이라 건수 대조가 가능하다
(이메일 250 + 포인트 250).

**before**

- 오류 처리 없음 → 첫 오염 행(`id = 200`)에서 예외 발생, Step 전체 `FAILED`
- 첫 청크(1~100)만 커밋된 채 멈춘다. 어디까지 처리됐는지는 `processed` 를 세어 봐야 알고, **어느 행이 오염이었는지는 아무 데도 남지 않는다**
- 재실행해도 같은 자리에서 다시 죽는다. 사람이 데이터를 고치기 전에는 끝나지 않는다

**after**

- `.faultTolerant().skip(MemberValidationException.class).skipLimit(1000)`
- 일시 오류는 `.retry(TransientDataAccessException.class).retryLimit(3)` 로 **구분**한다. 스킵하면 멀쩡한 행을 잃는다
- 스킵 목록에 없는 예외(코드 버그 등)는 그대로 Step 을 실패시킨다. `Exception` 으로 넓히면 배치가 절반을 버리고도 `COMPLETED` 로 끝난다
- `ErrorRowIsolatingSkipListener` 가 스킵된 행을 `member_b_error` 에 원인·단계·원문과 함께 격리 → Step 은 `COMPLETED`, 오염 행은 사후 추적 가능

**청크 100 은 양쪽 공통이다.** 이 문제의 차이는 오직 "오류를 견디는가" 하나여야 한다. 100인 이유는 오염이 200행마다 있어 **첫 청크는 커밋되고 두 번째 청크에서 죽는** 상황이 before 의 증상이기
때문이다. 500이면 첫 청크에서 죽어 한 행도 커밋되지 않는 다른 이야기가 된다.

**측정 지표** — Step 종료 상태, 처리/스킵/재시도 건수, 격리 테이블 적재 건수. 대사식이 맞아야 한다.

```
읽은 수 = 쓴 수 + 스킵 수          스킵 수 = 격리 테이블 행 수
처리되지 않은 행 = 오염 행, 그것도 정확히 그 행들
```

**실행**

```bash
# 0. 시딩 (한 번만). member_b 가 비어 있으면 skipJob 은 시작하지 않는다.
./gradlew bootRun --args="--spring.batch.job.enabled=true --spring.batch.job.name=seedJob target=member_b"

# before
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=before --spring.batch.job.name=skipJob'

# 리셋: 데이터를 지우지 않는다. 처리 표시만 되돌린다 (재시딩하면 오염 분포까지 다시 만들어야 한다)
#   UPDATE member_b SET processed = 0, updated_at = NULL;

# after
./gradlew bootRun --args='--spring.batch.job.enabled=true --spring.profiles.active=after --spring.batch.job.name=skipJob'
```

**장애 주입 Job 파라미터** — 재시도 경로를 재현한다. 진짜 락 타임아웃은 타이밍이 실행마다 달라져 같은 축에서 비교할 수 없으므로 결정론적으로 심는다. before/after 공통이며 기본값은 "주입 안 함" 이다.

| 파라미터     | 기본값        | 설명                                                                                |
|--------------|---------------|-------------------------------------------------------------------------------------|
| `faultAtId`  | `0` (주입 안 함) | 이 `id` 로 시작하는 청크의 **쓰기**를 실패시킨다                                    |
| `faultTimes` | `2`           | 실패 횟수. `retryLimit=3` 안이면 재시도로 회복, 넘으면 Step 실패                     |
| `faultKind`  | `TRANSIENT`   | `TRANSIENT` = 재시도 대상, `FATAL` = 스킵도 재시도도 아닌 예외 (Step 이 실패해야 정상) |

```bash
# 일시 장애 2회 → 재시도로 회복 (COMPLETED)
./gradlew bootRun --args='... --spring.profiles.active=after --spring.batch.job.name=skipJob' faultAtId=50001 faultTimes=2

# 분류되지 않은 예외 → 스킵되지 않고 실패 (FAILED 가 정상이다)
./gradlew bootRun --args='... --spring.profiles.active=after --spring.batch.job.name=skipJob' faultAtId=50001 faultKind=FATAL
```

**측정 쿼리**

```sql
SELECT s.STEP_NAME, s.STATUS, s.READ_COUNT, s.WRITE_COUNT,
       s.PROCESS_SKIP_COUNT, s.ROLLBACK_COUNT, s.COMMIT_COUNT
FROM BATCH_STEP_EXECUTION s
ORDER BY s.STEP_EXECUTION_ID DESC;

SELECT COUNT(*) FROM member_b WHERE processed = 1;                    -- 실제로 반영된 건수
SELECT step_execution_id, phase, COUNT(*) FROM member_b_error GROUP BY 1, 2;
SELECT SUBSTRING_INDEX(message, ':', 1) AS rule, COUNT(*)
FROM member_b_error GROUP BY 1;                                       -- EMAIL_FORMAT / NEGATIVE_POINT
```

격리 기록은 실행마다 누적된다. 지우지 않는 이유는 격리의 목적이 사후 추적이기 때문이고, 어느 실행의 기록인지는 `step_execution_id` 로 구분한다.

**스킵의 대가는 어디로 오는가** — 10만 건 실측 (`rollback` 500 = 스킵 500, `commit` 1,001 = 청크 1,000 + 1). 같은 성질을 1000건 규모에서 `AfterSkipJobTest` 가 고정한다. 상세한 해석은 문서 맨
아래 비교 기록에 있다.

가공 단계 스킵은 그 청크를 **통째로 롤백**시킨 뒤 다시 처리한다. 재처리된 청크가 결국 한 번 커밋되므로 **커밋 횟수는 늘지 않고**, 리더도 다시 부르지 않는다(재처리는 캐시된 청크로 한다). 즉 대가는
커밋 폭증이 아니라 **롤백 한 번과 최대 100건의 헛된 재검증**이다. 이 비용을 더 줄이는 다음 지렛대는 `.noRollback(...)` 이지만, 프로세서가 부수 효과를 갖게 되면 조용히 틀리는 설정이라 여기서는
쓰지 않는다.

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

| 문제 | Job         | 프로파일 | 총 소요 시간                 | 쿼리/커밋 횟수                                                                                                  | CPU | IO                                                | 비고                                                                               |
|------|-------------|----------|------------------------------|-----------------------------------------------------------------------------------------------------------------|-----|---------------------------------------------------|------------------------------------------------------------------------------------|
| 1    | `insertJob` | before   | **211s** (Step, 4.7k rows/s) | INSERT 1,000,002 (**행당 1.0000**) / 커밋 10,001 / 메타데이터 UPDATE 20,006 + SELECT 10,007 = **총 왕복 104만** |     | **1,164 MiB** (44,534 pages, 행당 1,221 B)        | 인덱스 선생성 + `chunk(100)` + `JpaItemWriter`                                     |
| 1    | `insertJob` | after    | **8s** (Job 전체, **26× ↓**) | INSERT 202 (**행당 0.0002**) / 커밋 201 / 메타데이터 UPDATE 406 + SELECT 207 = **총 왕복 1,024 (1,016× ↓)**     |     | **133 MiB** (8,104 pages, 행당 140 B, **8.7× ↓**) | 인덱스 후생성 + `chunk(5000)` + `JdbcBatchItemWriter` + `rewriteBatchedStatements` |
| 2    | `skipJob`   | before          | **0.04s** (Step. 100건에서 멈춤)  | UPDATE 107 / 커밋 1 / **롤백 1** / 스킵 0 / 격리 0                                                              |     | **0.0 MiB**                                       | `FAILED`. 10만 건 중 **100건만** 반영, 오염 행 정보 없음                          |
| 2    | `skipJob`   | after           | **6.3s** (Step. 99,500건 완주)    | UPDATE 101,507 (행당 1.02) / 커밋 1,001 / **롤백 500** / 스킵 500 / 격리 INSERT 500                              |     | **41.2 MiB**                                      | `COMPLETED`. 대사 완전 일치, 오염 500건 격리                                      |
| 2    | `skipJob`   | after (+장애 2회) | **5.7s** (Step)                  | 위와 동일 / **롤백 502** (스킵 500 + 재시도 2)                                                                  |     | 10.6 MiB (백그라운드 flush 타이밍 차)             | `faultAtId=50001 faultTimes=2` → 재시도로 회복, 격리 테이블에 WRITE 기록 없음     |

### 1번 문제에서 읽어야 할 것

- **왕복은 1,000배 줄었는데 IO 는 8.7배만 줄었다.** 100만 행의 데이터는 어느 쪽이든 디스크에 써야 한다. after 의 133 MiB 는 행 데이터 (약 100 MiB) + 인덱스 3개
  구축분으로, 사실상 이론적 하한이다. 거꾸로 before 의 1,164 MiB 중 90%는 일 자체가 아니라 **일하는 방식의 대가**였다. 랜덤 인덱스 갱신이 같은 페이지를 반복해서 더럽히고, 1만 번의 커밋이
  매번 로그를 flush 했다.
- **청크 크기는 INSERT 만 묶는 것이 아니다.** 커밋마다 따라붙던 배치 메타데이터 왕복 (커밋당 UPDATE 2 + SELECT 1)이 30,013 → 613 으로 줄었다. 라이터를 바꿔서 얻은 것이
  아니라 순전히 커밋 횟수의 효과다. 이 비용은 Step 통계에 잡히지 않아 놓치기 쉽다.
- **`INSERT 202` 는 청크 200개당 문장 1개**다. `rewriteBatchedStatements=true` 가 5000행을 한 패킷에 담았다는 뜻이다. 이 옵션이 없으면
  `JdbcBatchItemWriter` 를 써도 드라이버가 문장을 하나씩 보낸다.
  <b>코드만 바꿔서는 개선되지 않는다.</b>
- **인덱스 후생성 비용은 측정 해상도 아래였다.** after 의 Step 시간과 Job 시간이 둘 다 8초로, 100만 행에 인덱스 3개 (UK 포함)를 만드는 비용이 초 단위로는 드러나지 않는다. 정렬된
  데이터를 한 번 훑어 구축하는 것과, 적재 내내 랜덤 위치를 갱신하는 것의 차이가 여기 있다. 어느 쪽이든 after 를 평가할 때는 Step 이 아니라 **Job 시간**을 봐야 공정하다 (before 는
  인덱스를 빈 테이블에 만들므로 Step ≈ Job).

### 2번 문제에서 읽어야 할 것

- **before 의 0.04초는 성능 수치가 아니다.** 10만 건짜리 배치가 42밀리초 만에 끝났다면 그건 빠른 게 아니라 **일을 안 한 것**이다. 두 프로파일을 나란히 놓을 수 있는 축은 시간이 아니라
  `READ = WRITE + SKIP` 이 성립하는가 하나뿐이다. before 는 `200 = 100 + 0`, 즉 **100건이 증발한 상태로 끝났다.** 이 문제만은 "얼마나 빨랐나" 가 아니라 "끝났나" 를 본다.
- **가장 비싼 것은 사라진 정보다.** before 는 오염 500건 중 **첫 1건만** 로그의 스택트레이스에 남긴다. 나머지 499건이 어떤 행인지, 원인이 이메일인지 포인트인지 알 방법이 없다. after 의
  격리 테이블은 500건을 `EMAIL_FORMAT 250 / NEGATIVE_POINT 250` 으로 분류해 두므로, 데이터 담당자가 배치를 다시 돌리지 않고도 무엇을 고쳐야 할지 안다. **격리 INSERT 500회가 이
  실습에서 가장 값싼 지출이다.**
- **격리의 대가는 롤백이지 커밋이 아니었다.** 스킵 500건에 대해 롤백이 정확히 500회 일어났지만 커밋은 1,001회 (= 청크 1,000 + 1) 로 **스킵이 없을 때와 같다.** 롤백된 청크가 다시
  처리되어 결국 한 번 커밋되기 때문이다. `READ_COUNT` 도 정확히 10만이다 — 재처리는 리더가 아니라 캐시된 청크에서 온다. 설계 단계에서는 "행 단위 스캔 때문에 커밋이 5만 번" 을 예상했는데,
  그 스캔은 <b>쓰기 단계 스킵</b>의 이야기이지 가공 단계 스킵의 이야기가 아니었다.
- **롤백된 청크의 UPDATE 는 왕복에 없다.** `COM_UPDATE 101,507 ≈ 99,500(행) + 2,002(커밋당 메타데이터 2)`. 오차 5를 빼면 헛되이 보낸 UPDATE 가 한 건도 없다는 뜻이다.
  검증이 쓰기 <em>전</em>에 터지므로 롤백은 DB 왕복을 낭비하지 않는다. 실제 대가는 **최대 100건의 헛된 재검증(CPU)** 이고, 그래서 초 단위 시간에는 거의 드러나지 않는다.
- **재시도 2회의 비용도 측정 해상도 아래였다.** 장애를 심은 실행이 오히려 5.7초로 더 빨랐다 (실행 간 편차가 재시도 비용보다 크다). 드러난 것은 시간이 아니라 `ROLLBACK_COUNT` 가
  500 → 502 로 바뀐 것뿐이다. **일시 오류를 스킵으로 처리했다면 이 두 번의 흔들림 때문에 멀쩡한 100건이 격리 테이블로 갔을 것이다.** 스킵과 재시도를 나누는 이유가 여기 있다.
- **UPDATE 는 행당 1회 왕복한다** (`101,507 / 99,500 ≈ 1.02`). `rewriteBatchedStatements=true` 는 INSERT 를 다시 쓸 뿐 UPDATE 를 묶지 않기 때문이다. 이건 2번 문제의
  결함이 아니라 **6번 문제(대량 UPDATE 쓰기 경로)의 주제**이며, before/after 가 같은 라이터를 쓰므로 비교 축에는 영향이 없다.
- **재실행해도 결과가 같다.** 같은 데이터로 두 번 돌린 실행(6번, 7번)의 `READ/WRITE/SKIP/COMMIT/ROLLBACK` 이 완전히 동일하다. 쓰기가 `SET processed = 1` 이라 멱등이고,
  격리 기록만 `step_execution_id` 로 구분되어 쌓인다. 측정을 다시 하고 싶으면 `UPDATE member_b SET processed = 0, updated_at = NULL` 만 하면 된다 — **재시딩이 필요 없다.**

**측정 방법 두 가지.** Step 통계는 배치 메타데이터에서 읽는다.

```sql
SELECT s.STEP_NAME,
       s.READ_COUNT,
       s.WRITE_COUNT,
       s.COMMIT_COUNT,
       TIMESTAMPDIFF(SECOND, s.START_TIME, s.END_TIME) AS SECONDS
FROM BATCH_STEP_EXECUTION s
ORDER BY s.STEP_EXECUTION_ID DESC;
```

쿼리 왕복 횟수와 디스크 write IO 는 배치가 모르는 값이므로 `DatabaseWorkloadListener` 가 Job 전후의
`SHOW GLOBAL STATUS` 차이를 로그로 남긴다. 모든 Job 에 같은 리스너를 맨 앞에 등록해 측정 범위를 Job 전체 (= after 의 인덱스 후생성 비용 포함)로 맞춘다.

> 상태 카운터는 서버 전역이다. 다른 작업이 붙어 있지 않은 DB 에서 측정한다.
> `INNODB_PAGES_WRITTEN` 은 백그라운드 flush 타이밍에 좌우되므로 절대값보다 before/after 배율로 본다.
