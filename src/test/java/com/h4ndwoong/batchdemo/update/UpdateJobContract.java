package com.h4ndwoong.batchdemo.update;

import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code updateJob} 의 before/after 가 <b>똑같이 만족해야 하는</b> 결과.
 *
 * <p>여기 있는 시험은 전부 "두 프로파일이 같은 일을 한다" 의 확인이다 — 같은 규칙으로 등급을 매기고,
 * 같은 행을 갱신하고, 건드리지 말아야 할 것을 건드리지 않는다. <b>이것이 서지 않으면 왕복 수만 배
 * 감소는 아무 의미가 없다.</b> 6번에서 이 계약은 특히 위태롭다 — after 는 등급 규칙을 SQL 의
 * {@code CASE} 식으로 옮겨 실행하므로, 규칙이 이관 과정에서 어긋나면 <b>배치는 성공으로 끝나고
 * 등급만 조용히 틀린다.</b>
 *
 * <p>두 프로파일이 갈라지는 지점(왕복 횟수, Step 통계의 의미)은 각 하위 클래스가 확인한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class UpdateJobContract {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job updateJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected GradeRecalcReporter reporter;

    @Autowired
    protected DatabaseWorkloadListener workloadListener;

    /**
     * 이 테스트 메서드 전용 JobInstance 식별자.
     *
     * <p>메타데이터 DB 는 테스트 사이에 남으므로, 값이 겹치면 앞선 메서드가 완료해 둔 인스턴스를
     * 다시 실행하려다 실패한다 (5번 계약과 같은 장치다).
     */
    private final long runId = System.nanoTime();

    @BeforeEach
    void 데이터를_채운다() {
        UpdateFixture.seed(jdbcTemplate);
    }

    /**
     * 테이블을 비우고 부록 측정용 인덱스도 되돌린다.
     *
     * <p>인덱스를 남기면 다음 테스트가 "인덱스가 있는 상태" 에서 시작한다. Job 이 시작 시 맞추기는
     * 하지만, 뒤처리를 다음 실행에 미루지 않는다.
     */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("ALTER TABLE member_f DROP INDEX IF EXISTS idx_member_f_grade_point");
        jdbcTemplate.execute("TRUNCATE TABLE member_f");
    }

    @Test
    @DisplayName("전량의 등급을 규칙대로 다시 매기고 COMPLETED 로 끝난다")
    void 정상_1회_실행() throws Exception {
        JobExecution execution = launch(run());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reporter.current())
                .as("등급 분포·갱신 행 수·포인트 총합이 모두 기대와 같아야 한다")
                .isEqualTo(UpdateFixture.expectedChecksum());
    }

    @Test
    @DisplayName("모든 행이 정책이 정한 등급을 갖는다 - DB 에서 독립적으로 검산한다")
    void 규칙_위반_행이_없다() throws Exception {
        launch(run());

        assertThat(violatingRows())
                .as("자바로 계산한 기대값과 별개로, 조건에 어긋나는 행이 한 건도 없어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("포인트는 건드리지 않는다 - 6번이 바꾸는 것은 grade 뿐이다")
    void 포인트는_그대로다() throws Exception {
        launch(run());

        assertThat(reporter.current().pointSum())
                .isEqualTo(UpdateFixture.seededChecksum().pointSum());
    }

    @Test
    @DisplayName("등급이 이미 옳던 행은 updated_at 이 NULL 로 남는다 - 갱신 행 수가 양쪽 같아야 한다")
    void 바뀌지_않은_행은_쓰지_않는다() throws Exception {
        launch(run());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_f WHERE updated_at IS NULL", Long.class))
                .as("이 값이 프로파일마다 다르면 '같은 일을 덜 나눠 보냈다' 가 성립하지 않는다")
                .isEqualTo(UpdateFixture.COUNT - UpdateFixture.changedCount());
    }

    @Test
    @DisplayName("다시 실행해도 아무것도 바뀌지 않는다 - 같은 포인트는 같은 등급이라 자연 멱등이다")
    void 재실행은_멱등이다() throws Exception {
        launch(run());
        GradeRecalcChecksum afterFirst = reporter.current();

        JobExecution second = launch(nextRun());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(reporter.current())
                .as("5번과 달리 설계 없이도 멱등하다. 읽기 조건이 아니라 계산이 데이터에만 의존하기 때문이다")
                .isEqualTo(afterFirst);
        assertThat(reporter.afterChecksum().changedRows() - reporter.beforeChecksum().changedRows())
                .as("두 번째 실행은 갱신할 행이 없다")
                .isZero();
    }

    @Test
    @DisplayName("member_f 가 비어 있으면 Step 에 진입하지 않는다 - 0건 성공은 개선과 구분되지 않는다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE member_f");

        JobExecution execution = launch(run());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_f"));
    }

    @Test
    @DisplayName("Step 이름이 양쪽 같다 - 메타데이터를 한 축에서 읽기 위한 전제")
    void step_이름() throws Exception {
        assertThat(step(launch(run())).getStepName()).isEqualTo("updateStep");
    }

    /**
     * 정책을 어긴 행 수. 정책 임계값을 DB 에서 다시 산출해 조건으로 대조한다.
     *
     * @return 위반 행 수. 0 이어야 한다
     */
    protected long violatingRows() {
        var policy = UpdateFixture.policy();
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM member_f
                WHERE grade <> CASE WHEN point >= ? THEN 'VIP'
                                    WHEN point >= ? THEN 'GOLD'
                                    WHEN point >= ? THEN 'SILVER'
                                    ELSE 'BRONZE' END""",
                Long.class, policy.vipFrom(), policy.goldFrom(), policy.silverFrom());
        return count == null ? -1 : count;
    }

    /**
     * Job 을 실행한다.
     *
     * @param parameters Job 파라미터
     * @return 실행 결과
     */
    protected JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(updateJob, parameters);
    }

    /**
     * 이 테스트의 JobInstance 를 가리키는 파라미터.
     *
     * @return 파라미터
     */
    protected JobParameters run() {
        return new JobParametersBuilder().addLong("run.id", runId).toJobParameters();
    }

    /**
     * 다음 회차의 JobInstance 를 가리키는 파라미터. 재실행이 된다.
     *
     * @return 파라미터
     */
    protected JobParameters nextRun() {
        return new JobParametersBuilder().addLong("run.id", runId + 1).toJobParameters();
    }

    /**
     * 실행의 유일한 Step.
     *
     * @param execution 실행 결과
     * @return Step 실행
     */
    protected StepExecution step(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }
}
