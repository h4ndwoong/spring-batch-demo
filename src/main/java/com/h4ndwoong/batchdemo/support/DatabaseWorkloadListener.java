package com.h4ndwoong.batchdemo.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Job 전후의 DB 상태 카운터 차이를 기록한다. <b>쿼리 왕복 횟수와 디스크 write IO</b> 를 재는 장치다.
 *
 * <p>Step 통계({@code BATCH_STEP_EXECUTION})는 배치가 <em>스스로 안다고 생각하는</em> 것만 담는다.
 * 읽은 건수, 쓴 건수, 커밋 횟수까지다. 정작 1번 문제의 핵심인 "행 수만큼 왕복했는가" 와
 * "디스크에 얼마나 썼는가" 는 DB 만 알고 있으므로 여기서 따로 읽는다.
 *
 * <p><b>가장 중요한 지표는 "INSERT 문 수 / 쓴 아이템 수" 비율</b>이다.
 * <ul>
 *   <li>1.00 — 행마다 INSERT 문을 하나씩 보냈다 (before). 왕복이 곧 행 수다.</li>
 *   <li>0.001 — 1000행을 한 문장에 묶어 보냈다 (after 의 {@code rewriteBatchedStatements=true}).
 *       같은 행 수를 적재하면서 왕복만 줄었다는 뜻이다.</li>
 * </ul>
 * 쓴 아이템 수는 양쪽이 같아야 하므로, 이 비율은 "일을 덜 한 것"과 "일을 잘 묶은 것"을 구분해준다.
 * 분모를 서버 카운터가 아니라 {@code StepExecution} 의 {@code writeCount} 에서 가져오는 이유는,
 * 그것이 <b>이 Job 이 쓴 것</b>만 세는 유일한 값이기 때문이다. 서버 카운터는 전역이라 다른 세션의
 * 몫이 섞인다.
 *
 * <p><b>측정 범위</b><br>
 * {@code beforeJob} 에서 {@code afterJob} 까지다. Job 리스너를 등록 순서상 <b>맨 앞</b>에 두면
 * Spring Batch 가 {@code afterJob} 을 역순으로 호출하므로 다른 리스너의 작업까지 범위에 들어온다.
 * after 프로파일이 적재 <em>후</em>에 인덱스를 만드는 것도 비용이므로 이 범위에 포함되어야 공정하다.
 *
 * <p><b>한계</b><br>
 * 상태 카운터는 <em>서버 전역</em>이다. 같은 MariaDB 인스턴스에 다른 작업이 붙어 있으면 그 몫까지
 * 섞인다. 측정은 유휴 상태의 DB 에서 한다. {@code INNODB_PAGES_WRITTEN} 은 백그라운드 flush 도
 * 포함하므로 Job 이 끝난 직후의 값이 아직 덜 반영되었을 수 있다 (더티 페이지가 남아 있으면 그렇다).
 * 절대값보다 before/after 의 <b>배율</b>을 본다.
 */
public class DatabaseWorkloadListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWorkloadListener.class);

    /** 보내진 INSERT <b>문</b>의 수. 행 수가 아니라 왕복 횟수다. */
    public static final String INSERT_STATEMENTS = "COM_INSERT";

    /**
     * bulk 프로토콜({@code COM_STMT_BULK_EXECUTE})로 보낸 명령 수.
     *
     * <p>MariaDB 드라이버는 배치를 <em>SQL 문을 다시 쓰는 방식</em>(여러 VALUES 를 한 INSERT 로)
     * 또는 <em>전용 바이너리 프로토콜</em>({@code useBulkStmts=true})로 보낼 수 있다. 후자를 세기
     * 위해 넣어 둔 이름이다.
     *
     * <p><b>다만 MariaDB 11.8.8 의 {@code SHOW GLOBAL STATUS} 에는 이 변수가 없다</b> (6번 문제를
     * 구현하며 확인했다). 그리고 bulk 프로토콜로 보내도 <b>서버가 세는 문장 수는 줄지 않는다</b> —
     * 20,000행을 bulk 로 보낸 실측에서 {@code Com_update} 가 그대로 20,000 이었고
     * {@code Com_stmt_execute} 가 20,000 늘었다. 즉 이 카운터 계열이 말하는 것은 <b>네트워크 패킷
     * 수가 아니라 서버가 실행한 문장 수</b>이며, 연결 설정은 그 값을 바꾸지 못한다 (줄어드는 것은
     * 시간이다). 없는 이름은 조용히 빠지므로 목록에 남겨 둔다.
     */
    public static final String BULK_STATEMENTS = "COM_STMT_BULK_EXECUTE";

    /**
     * <b>서버가 실행한</b> UPDATE 문의 수. 행 수가 아니며 <b>6번 문제의 비교 축</b>이다.
     *
     * <p>"왕복" 이라고 부르기 쉽지만 정확히는 <b>문장 수</b>다. 6번을 구현하며 잰 결과, 연결 설정
     * ({@code useBulkStmts=true})으로 배치를 패킷 하나에 묶어도 이 값은 그대로였다 — 드라이버가
     * 묶어 보낸 것을 서버는 여전히 행 수만큼의 문장으로 실행한다. 그래서 <b>이 카운터를 줄이는
     * 방법은 문장 자체를 줄이는 것뿐</b>이고, 그것이 6번 after 의 집합 UPDATE 다.
     * <ul>
     *   <li>before — 갱신 행마다 1 (약 75만)</li>
     *   <li>after — 슬라이스마다 1 (기본 20)</li>
     * </ul>
     *
     * <p>{@link #ROWS_UPDATED} 와 반드시 함께 본다. 문장 수만 줄고 갱신 행 수도 함께 줄었다면
     * 그것은 개선이 아니라 일을 빠뜨린 것이다.
     */
    public static final String UPDATE_STATEMENTS = "COM_UPDATE";

    /**
     * 인덱스 순서로 "다음 행" 을 읽은 횟수. <b>3번 문제(offset 페이징)의 주 지표</b>다.
     *
     * <p>3번의 before/after 는 쿼리 <em>수</em>가 같다. {@code LIMIT 1000 OFFSET 1999000} 도
     * {@code WHERE id > ? LIMIT 1000} 도 SELECT 한 번이다. 다른 것은 그 한 번이 <b>몇 행을 읽고
     * 버리는가</b> 이고, 그 값은 배치도 애플리케이션 로그도 모른다. 이 카운터만 안다.
     * <ul>
     *   <li>offset — 전체 순회에 N²/(2×페이지크기) 행. 200만 건·1,000행 페이지면 약 20억</li>
     *   <li>키셋 — N 행. 200만</li>
     * </ul>
     *
     * <p>{@code Innodb_rows_read} 를 쓰지 않은 이유는 MariaDB 11.8.8 의 {@code SHOW GLOBAL STATUS}
     * 에 그 변수가 없기 때문이다. {@code Handler_*} 계열은 스토리지 엔진과 무관하게 서버가 세므로
     * 항상 있다.
     */
    public static final String ROWS_SCANNED = "HANDLER_READ_NEXT";

    /**
     * 인덱스로 행을 <b>찾은</b> 횟수(탐색). <b>4번 문제(Processor N+1)의 보조 지표</b>다.
     *
     * <p>4번의 before/after 는 {@link #ROWS_SCANNED} 로는 거의 구분되지 않는다. 양쪽 다 추천인을
     * PK 로 한 건씩 찾을 뿐이라 <em>읽는 행 수</em>가 비슷하기 때문이다. 달라지는 것은 그 탐색을
     * <b>몇 번의 왕복에 나눠 담았는가</b>이고, 그것은 {@code COM_SELECT} 가 말해준다.
     * <ul>
     *   <li>{@code COM_SELECT} — before 는 행당 2, after 는 청크당 1. <b>1,000배 차이</b></li>
     *   <li>이 카운터 — before 는 행당 2, after 도 행당 1 수준. <b>2배 차이</b></li>
     * </ul>
     * 둘을 나란히 봐야 "왕복을 줄인 것이지 DB 가 할 일을 없앤 것이 아니다" 가 드러난다.
     * 1번 문제에서 왕복이 1,016배 줄 때 IO 는 8.7배만 줄었던 것과 같은 종류의 눈금이다.
     */
    public static final String INDEX_LOOKUPS = "HANDLER_READ_KEY";

    /**
     * 인덱스를 타지 않고 다음 행을 읽은 횟수(풀 스캔).
     *
     * <p>{@link #ROWS_SCANNED} 와 함께 본다. 이 값이 크면 의도한 인덱스를 안 타고 있다는 뜻이라,
     * 측정하려던 것과 다른 것을 재고 있을 수 있다.
     */
    public static final String ROWS_SCANNED_NO_INDEX = "HANDLER_READ_RND_NEXT";

    /**
     * 서버가 갱신한 <b>행</b>의 수. <b>6번 문제(대량 UPDATE 쓰기 경로)의 주 지표</b>다.
     *
     * <p>6번의 before/after 는 <em>같은 행 수를 갱신</em>한다. 달라지는 것은 그 갱신을 몇 번의
     * 왕복에 나눠 담았는가 하나뿐이다.
     * <ul>
     *   <li>{@code COM_UPDATE} — before 는 행당 1, after 는 슬라이스당 1. <b>수만 배 차이</b></li>
     *   <li>이 카운터 — <b>양쪽이 같아야 한다</b></li>
     * </ul>
     * 둘을 나란히 봐야 "왕복을 줄인 것이지 덜 갱신한 것이 아니다" 가 선다. 이 값이 어긋나면 after 는
     * 개선이 아니라 <b>일을 빠뜨린 버그</b>이고, 배치는 그래도 {@code COMPLETED} 로 끝난다.
     *
     * <p>4번의 {@link #INDEX_LOOKUPS} 와 같은 역할이며, {@code Innodb_rows_updated} 가 아니라
     * {@code Handler_*} 를 쓰는 이유도 같다 — MariaDB 11.8.8 의 {@code SHOW GLOBAL STATUS} 에는
     * {@code Innodb_rows_*} 계열이 없다.
     *
     * <p><b>전역 카운터다.</b> 배치 메타데이터 UPDATE(커밋당 2행 남짓)도 여기 섞인다. 절대값이
     * 아니라 "갱신 행 수와 왕복 횟수의 비" 로 읽는다.
     */
    public static final String ROWS_UPDATED = "HANDLER_UPDATE";

    /**
     * 행 잠금을 기다린 총 시간(밀리초). <b>6번 문제의 청구서</b>다.
     *
     * <p>집합 UPDATE 는 왕복을 없애는 대신 <b>락 단위를 키운다.</b> before 는 청크(1,000행)마다
     * 커밋해 락을 놓아주지만, 분할하지 않은 집합 UPDATE 한 문장은 대상 전체를 커밋까지 붙잡는다.
     * 1~5번에서 after 가 모든 항목에서 이겼던 것과 달리 <b>6번에는 after 가 지는 항목이 있고</b>,
     * 그것을 다시 다이얼로 만드는 것이 슬라이스 크기다.
     *
     * <p><b>경합이 없으면 0 이다.</b> 이 값은 <em>기다린</em> 시간이지 <em>잡고 있던</em> 시간이
     * 아니다. 유휴 DB 에서 배치 혼자 돌면 아무도 기다리지 않으므로 락 비용이 지표에 드러나지 않는다.
     * 잡고 있던 시간의 상한은 슬라이스별 문장 시간(after) 과 청크 시간(before) 으로 본다.
     */
    public static final String LOCK_WAIT_TIME = "INNODB_ROW_LOCK_TIME";

    /** 행 잠금을 기다린 횟수. {@link #LOCK_WAIT_TIME} 과 함께 본다. 경합이 없으면 0 이다. */
    public static final String LOCK_WAITS = "INNODB_ROW_LOCK_WAITS";

    /** 디스크에 쓴 페이지 수. */
    public static final String PAGES_WRITTEN = "INNODB_PAGES_WRITTEN";

    /** 디스크에 쓴 바이트 수. */
    public static final String BYTES_WRITTEN = "INNODB_DATA_WRITTEN";

    /**
     * 읽을 카운터. 1번 문제만이 아니라 7문제 전부의 지표를 한 벌로 모은다.
     * 6번(대량 UPDATE)은 {@link #UPDATE_STATEMENTS} 와 {@link #ROWS_UPDATED}, 4번(N+1 조회)은
     * {@code COM_SELECT} 와 {@link #INDEX_LOOKUPS}, 3번(offset 페이징)은 {@link #ROWS_SCANNED} 가
     * 주 지표다.
     *
     * <p>서버가 제공하지 않는 이름은 {@link #readCounters()} 에서 조용히 빠진다. 목록에 넣는 것은
     * 공짜이고, 없는 변수 때문에 Job 이 실패하지는 않는다.
     */
    private static final List<String> COUNTERS = List.of(
            INSERT_STATEMENTS, BULK_STATEMENTS, UPDATE_STATEMENTS, "COM_SELECT", "COM_COMMIT",
            ROWS_SCANNED, ROWS_SCANNED_NO_INDEX, INDEX_LOOKUPS, ROWS_UPDATED,
            LOCK_WAIT_TIME, LOCK_WAITS, PAGES_WRITTEN, BYTES_WRITTEN);

    /**
     * {@code information_schema.GLOBAL_STATUS} 가 아니라 {@code SHOW GLOBAL STATUS} 를 쓴다.
     * MariaDB 11.8 의 {@code information_schema} 뷰에는 스토리지 엔진이 내보내는 일부 변수가
     * 빠져 있어, 어떤 지표는 있고 어떤 지표는 없는 상태가 된다. {@code SHOW} 는 서버와 엔진의
     * 상태 변수를 합쳐서 돌려준다. 반환 행이 수백 개지만 Job 당 두 번만 부르므로 비용은 무시할 수 있다.
     */
    private static final String COUNTER_SQL = "SHOW GLOBAL STATUS";

    private static final long BYTES_IN_MIB = 1024L * 1024L;

    private final JdbcTemplate jdbcTemplate;

    /**
     * {@code beforeJob} 시점의 스냅샷. Job 은 프로세스당 하나씩 순차 실행되므로 필드로 들고 있어도
     * 된다 ({@code --spring.batch.job.name} 으로 하나만 지정해 실행하는 것이 이 실습의 실행 방식이다).
     */
    private Map<String, Long> snapshot = Map.of();

    /** 마지막으로 끝난 Job 의 측정 결과. 로그로만 남기면 테스트가 증상을 검증할 수 없다. */
    private Map<String, Long> lastDelta = Map.of();

    public DatabaseWorkloadListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 마지막으로 끝난 Job 의 카운터 증가분.
     *
     * <p>before 의 "행마다 왕복" 이나 after 의 "묶어서 왕복" 은 코드를 읽어서 주장할 것이 아니라
     * 숫자로 확인해야 하는 성질이다. 테스트가 그 숫자에 닿을 수 있게 열어 둔다.
     *
     * @return 카운터 이름과 증가분. Job 이 끝난 적이 없으면 빈 map
     */
    public Map<String, Long> lastDelta() {
        return Map.copyOf(lastDelta);
    }

    /**
     * 관심 있는 상태 카운터를 읽는다.
     *
     * @return 카운터 이름(대문자)과 값. 서버가 제공하지 않는 이름은 빠진다
     */
    public Map<String, Long> readCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        jdbcTemplate.query(COUNTER_SQL, resultSet -> {
            String name = resultSet.getString(1).toUpperCase(Locale.ROOT);
            if (COUNTERS.contains(name)) {
                counters.put(name, toLong(resultSet.getString(2)));
            }
        });
        return counters;
    }

    /** 값은 문자열로 온다. 숫자가 아닌 상태 변수도 있지만 {@link #COUNTERS} 는 모두 숫자다. */
    private static long toLong(String value) {
        return (long) Double.parseDouble(value.trim());
    }

    /** {@inheritDoc} */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        snapshot = readCounters();
    }

    /**
     * {@inheritDoc}
     *
     * <p>측정 실패가 Job 을 망치지 않게 한다. 이미 끝난 적재를 되돌릴 이유가 없다.
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            lastDelta = difference(snapshot, readCounters());
            log.info("\n{}", report(jobExecution, lastDelta));
        }
        catch (RuntimeException e) {
            log.warn("DB 작업량을 읽지 못했다. 적재 결과에는 영향이 없다.", e);
        }
    }

    private static Map<String, Long> difference(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> delta = new LinkedHashMap<>();
        COUNTERS.stream()
                .filter(before::containsKey)
                .filter(after::containsKey)
                .forEach(name -> delta.put(name, after.get(name) - before.get(name)));
        return delta;
    }

    private static String report(JobExecution jobExecution, Map<String, Long> delta) {
        StringBuilder report = new StringBuilder()
                .append("==== DB 작업량: ").append(jobExecution.getJobInstance().getJobName())
                .append(" (").append(jobExecution.getStatus()).append(") ====\n");

        delta.forEach((name, value) -> report
                .append(String.format("  %-22s %,15d%n", name, value)));

        long written = writtenItems(jobExecution);
        report.append(String.format("  %-22s %,15d%n", "쓴 아이템(Step 기준)", written));
        if (written > 0) {
            report.append(String.format("  %-22s %15.4f  (1.0 이면 행마다 왕복)%n",
                    "INSERT 왕복 / 아이템", (double) insertRoundTrips(delta) / written));
        }

        long updatedRows = delta.getOrDefault(ROWS_UPDATED, 0L);
        if (updatedRows > 0) {
            report.append(String.format("  %-22s %15.4f  (1.0 이면 행마다 왕복)%n",
                    "UPDATE 왕복 / 갱신 행", (double) updateRoundTrips(delta) / updatedRows));
        }
        report.append(String.format("  %-22s %15.1f MiB%n",
                "디스크 write", (double) delta.getOrDefault(BYTES_WRITTEN, 0L) / BYTES_IN_MIB));
        return report.toString();
    }

    /**
     * INSERT 를 위해 서버에 보낸 명령 수. SQL 재작성 경로와 bulk 프로토콜 경로의 합이다.
     *
     * @param delta {@link #lastDelta()} 같은 카운터 증가분
     * @return 왕복 횟수
     */
    public static long insertRoundTrips(Map<String, Long> delta) {
        return delta.getOrDefault(INSERT_STATEMENTS, 0L) + delta.getOrDefault(BULK_STATEMENTS, 0L);
    }

    /**
     * UPDATE 때문에 서버가 실행한 문장 수. SQL 문장 경로와 bulk 프로토콜 경로의 합이다.
     *
     * <p>MariaDB 11.8.8 에서는 {@link #BULK_STATEMENTS} 가 보고되지 않아 사실상
     * {@link #UPDATE_STATEMENTS} 와 같지만, 서버 버전이 그 변수를 내보내기 시작하면 한쪽만 보는
     * 실행이 "문장 0개" 로 보일 수 있으므로 합으로 읽는다 ({@link #insertRoundTrips(Map)} 과 같은
     * 방어다).
     *
     * @param delta {@link #lastDelta()} 같은 카운터 증가분
     * @return 문장 수
     */
    public static long updateRoundTrips(Map<String, Long> delta) {
        return delta.getOrDefault(UPDATE_STATEMENTS, 0L) + delta.getOrDefault(BULK_STATEMENTS, 0L);
    }

    /** 이 Job 이 실제로 쓴 아이템 수. 왕복 횟수의 분모이며 배치만이 아는 값이다. */
    private static long writtenItems(JobExecution jobExecution) {
        return jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();
    }
}
