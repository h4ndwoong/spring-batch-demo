package com.h4ndwoong.batchdemo.restart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step 전후로 {@code member_e} 전체를 집계해 {@link PointBalanceChecksum} 을 남긴다. <b>양쪽 공통</b>이다.
 *
 * <p>측정 장치가 프로파일별로 나뉘면 한쪽만 재거나 다르게 재는 사고가 난다
 * ({@code MeasurementConfig} 와 같은 이유). 무엇보다 5번의 대사는 <b>실행과 실행 사이</b>에서
 * 이루어지므로, 세는 방법이 실행마다 같다는 것이 대사의 전제다.
 *
 * <p><b>왜 Step 이 <em>실패해도</em> 값이 남는가</b><br>
 * {@link #afterStep(StepExecution)} 은 Step 이 실패해도 호출된다. 5번에서 가장 중요한 관측 중
 * 하나가 "실패한 실행이 어디까지 반영했는가" 이므로, 실패한 실행의 지문이야말로 반드시 남아야 한다.
 *
 * <p><b>전체 스캔 2회의 비용을 감수한다.</b> 30만 행 집계가 Step 앞뒤로 한 번씩 붙는다. 5번의 주
 * 지표는 시간이 아니라 <em>값</em>이고, 이 비용은 before/after 에 똑같이 걸리므로 비교 축을 흔들지
 * 않는다. 3·4번처럼 시간을 다투는 문제였다면 이런 리포터를 Step 에 붙이지 않았을 것이다.
 *
 * <p><b>{@code @StepScope} 가 아니다.</b> Step 이 끝난 뒤 테스트가 같은 인스턴스에서 지문을 읽어야
 * 한다 (4번의 {@code GradeDecisionItemWriter} 와 같은 이유). 매 Step 시작 시 두 필드를 다시 채우므로
 * 싱글턴으로 충분하다.
 */
public class PointBalanceReporter implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PointBalanceReporter.class);

    /**
     * 전체 지문을 한 번에 얻는 집계.
     *
     * <p>대상을 {@code status = 'ACTIVE'} 로 좁히지 <b>않는다.</b> 배치가 건드리지 말아야 할 행까지
     * 포함해서 세야 "건드리지 않았다" 를 확인할 수 있다.
     */
    private static final String CHECKSUM_SQL = """
            SELECT COUNT(*)                                                   AS row_count,
                   COALESCE(SUM(point), 0)                                    AS point_sum,
                   COALESCE(SUM(CASE WHEN point < 0 THEN 1 ELSE 0 END), 0)    AS negative_rows,
                   COALESCE(SUM(CASE WHEN processed = 1 THEN 1 ELSE 0 END), 0) AS processed_rows
            FROM member_e""";

    private final JdbcTemplate jdbcTemplate;

    private PointBalanceChecksum before = PointBalanceChecksum.EMPTY;
    private PointBalanceChecksum after = PointBalanceChecksum.EMPTY;

    /**
     * 리포터를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public PointBalanceReporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 지문을 지운다. 지우지 않으면 "읽을 것이 없어 Step 에 들어가지도 못한 실행" 의
     * 보고에 직전 실행의 값이 남는다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        before = current();
        after = PointBalanceChecksum.EMPTY;
        log.info("소멸 전 잔액 체크섬: {}", before.summary());
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code null} 을 돌려주어 {@code ExitStatus} 를 그대로 둔다. 실패한 Step 을 이 리스너가
     * 성공으로 바꿔서는 안 된다.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        after = current();
        log.info("소멸 후 잔액 체크섬: {} (직전 대비 {})", after.summary(), after.pointDelta(before));
        return null;
    }

    /**
     * 지금 이 순간의 지문. Step 과 무관하게 언제든 잴 수 있다.
     *
     * @return 지문
     */
    public PointBalanceChecksum current() {
        return jdbcTemplate.queryForObject(CHECKSUM_SQL, (rs, rowNum) -> new PointBalanceChecksum(
                rs.getLong("row_count"),
                rs.getLong("point_sum"),
                rs.getLong("negative_rows"),
                rs.getLong("processed_rows")));
    }

    /**
     * 마지막 Step 이 시작되기 직전의 지문.
     *
     * @return 지문. Step 에 진입한 적이 없으면 {@link PointBalanceChecksum#EMPTY}
     */
    public PointBalanceChecksum beforeChecksum() {
        return before;
    }

    /**
     * 마지막 Step 이 끝난 직후의 지문. <b>Step 이 실패했어도 남는다.</b>
     *
     * @return 지문. Step 에 진입한 적이 없으면 {@link PointBalanceChecksum#EMPTY}
     */
    public PointBalanceChecksum afterChecksum() {
        return after;
    }
}
