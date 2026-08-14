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
     * 또는 <em>전용 바이너리 프로토콜</em>로 보낼 수 있다. 후자는 {@code Com_insert} 를 올리지 않아,
     * 이 카운터가 없으면 "왕복이 0" 이라는 착시가 생긴다. 두 경로의 합이 실제 왕복 횟수다.
     */
    public static final String BULK_STATEMENTS = "COM_STMT_BULK_EXECUTE";

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
     * 인덱스를 타지 않고 다음 행을 읽은 횟수(풀 스캔).
     *
     * <p>{@link #ROWS_SCANNED} 와 함께 본다. 이 값이 크면 의도한 인덱스를 안 타고 있다는 뜻이라,
     * 측정하려던 것과 다른 것을 재고 있을 수 있다.
     */
    public static final String ROWS_SCANNED_NO_INDEX = "HANDLER_READ_RND_NEXT";

    /** 디스크에 쓴 페이지 수. */
    public static final String PAGES_WRITTEN = "INNODB_PAGES_WRITTEN";

    /** 디스크에 쓴 바이트 수. */
    public static final String BYTES_WRITTEN = "INNODB_DATA_WRITTEN";

    /**
     * 읽을 카운터. 1번 문제만이 아니라 7문제 전부의 지표를 한 벌로 모은다.
     * 6번(대량 UPDATE)은 {@code COM_UPDATE}, 4번(N+1 조회)은 {@code COM_SELECT},
     * 3번(offset 페이징)은 {@link #ROWS_SCANNED} 가 주 지표다.
     */
    private static final List<String> COUNTERS = List.of(
            INSERT_STATEMENTS, BULK_STATEMENTS, "COM_UPDATE", "COM_SELECT", "COM_COMMIT",
            ROWS_SCANNED, ROWS_SCANNED_NO_INDEX, PAGES_WRITTEN, BYTES_WRITTEN);

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

    /** 이 Job 이 실제로 쓴 아이템 수. 왕복 횟수의 분모이며 배치만이 아는 값이다. */
    private static long writtenItems(JobExecution jobExecution) {
        return jobExecution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();
    }
}
