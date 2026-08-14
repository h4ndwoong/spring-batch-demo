package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.domain.MemberGrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.EnumMap;
import java.util.Map;

/**
 * Step 전후로 {@code member_f} 전체를 집계해 {@link GradeRecalcChecksum} 을 남긴다. <b>양쪽 공통</b>이다.
 *
 * <p>측정 장치가 프로파일별로 나뉘면 한쪽만 재거나 다르게 재는 사고가 난다 ({@code MeasurementConfig}
 * 와 같은 이유). 6번은 특히 그렇다 — after 는 회원 행을 애플리케이션으로 가져오지 않으므로,
 * <b>배치가 스스로 아는 값(Step 통계)만으로는 두 프로파일을 비교할 수 없다.</b> before 의
 * {@code WRITE_COUNT} 에 대응하는 after 의 값은 여기서, 그리고 {@link SliceUpdateRecorder} 에서 나온다.
 *
 * <p><b>집계 한 번에 등급별로 묶어 읽는다.</b> 등급마다 {@code COUNT(*)} 를 따로 세면 전체 스캔이
 * 네 번이 된다. {@code GROUP BY grade} 는 한 번에 끝나고, 총합은 자바에서 더한다.
 *
 * <p><b>전체 스캔 2회의 비용을 감수한다.</b> 100만 행 집계가 Step 앞뒤로 한 번씩 붙는다. 이 비용은
 * before/after 에 똑같이 걸리므로 비교 축을 흔들지 않는다 (5번의 {@code PointBalanceReporter} 와
 * 같은 판단이다).
 */
public class GradeRecalcReporter implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(GradeRecalcReporter.class);

    /**
     * 등급별 지문을 한 번에 얻는 집계.
     *
     * <p>{@code updated_at IS NOT NULL} 을 세는 이유는 그것이 <b>"이 배치가 건드린 행"</b> 의 정의이기
     * 때문이다. 시딩 직후에는 전부 {@code NULL} 이고, 등급이 이미 옳던 행은 양쪽 프로파일 모두
     * 건드리지 않으므로 {@code NULL} 로 남는다.
     */
    private static final String CHECKSUM_SQL = """
            SELECT grade,
                   COUNT(*)                                                        AS row_count,
                   COALESCE(SUM(point), 0)                                         AS point_sum,
                   COALESCE(SUM(CASE WHEN updated_at IS NOT NULL THEN 1 ELSE 0 END), 0) AS changed_rows
            FROM member_f
            GROUP BY grade""";

    private final JdbcTemplate jdbcTemplate;

    private GradeRecalcChecksum before = GradeRecalcChecksum.EMPTY;
    private GradeRecalcChecksum after = GradeRecalcChecksum.EMPTY;

    /**
     * 리포터를 만든다.
     *
     * @param jdbcTemplate JDBC 템플릿
     */
    public GradeRecalcReporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>이전 실행의 지문을 지운다. 지우지 않으면 Step 에 진입하지도 못한 실행의 보고에 직전 실행의
     * 값이 남는다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        before = current();
        after = GradeRecalcChecksum.EMPTY;
        log.info("등급 재계산 전: {}", before.summary());
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code null} 을 돌려주어 {@code ExitStatus} 를 그대로 둔다. 실패한 Step 을 이 리스너가
     * 성공으로 바꿔서는 안 된다.
     *
     * @param stepExecution Step 실행. 쓰지 않는다
     * @return 언제나 {@code null}
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        after = current();
        log.info("등급 재계산 후: {} (갱신 {}행, 포인트 총합 변화 {})",
                after.summary(),
                after.changedRows() - before.changedRows(),
                after.pointSum() - before.pointSum());
        return null;
    }

    /**
     * 지금 이 순간의 지문. Step 과 무관하게 언제든 잴 수 있다.
     *
     * @return 지문
     */
    public GradeRecalcChecksum current() {
        Map<MemberGrade, Long> distribution = new EnumMap<>(MemberGrade.class);
        long[] totals = new long[3];

        jdbcTemplate.query(CHECKSUM_SQL, resultSet -> {
            long rows = resultSet.getLong("row_count");
            distribution.merge(MemberGrade.valueOf(resultSet.getString("grade")), rows, Long::sum);
            totals[0] += rows;
            totals[1] += resultSet.getLong("changed_rows");
            totals[2] += resultSet.getLong("point_sum");
        });

        return new GradeRecalcChecksum(totals[0], totals[1], totals[2], distribution);
    }

    /**
     * 마지막 Step 이 시작되기 직전의 지문.
     *
     * @return 지문. Step 에 진입한 적이 없으면 {@link GradeRecalcChecksum#EMPTY}
     */
    public GradeRecalcChecksum beforeChecksum() {
        return before;
    }

    /**
     * 마지막 Step 이 끝난 직후의 지문. <b>Step 이 실패했어도 남는다.</b>
     *
     * @return 지문. Step 에 진입한 적이 없으면 {@link GradeRecalcChecksum#EMPTY}
     */
    public GradeRecalcChecksum afterChecksum() {
        return after;
    }
}
