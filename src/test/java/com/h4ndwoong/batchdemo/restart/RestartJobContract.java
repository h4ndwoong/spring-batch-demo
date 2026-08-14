package com.h4ndwoong.batchdemo.restart;

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
 * {@code restartJob} 의 before/after 가 <b>똑같이 만족해야 하는</b> 결과.
 *
 * <p>여기 있는 시험은 전부 "두 프로파일이 같은 일을 한다" 의 확인이다 — 같은 대상을 고르고, 같은
 * 액수를 소멸시키고, 같은 지점에서 실패하고, <b>재시작하면 둘 다 정확히 완주한다.</b>
 *
 * <p><b>마지막 항목이 5번 문제의 함정이다.</b> before 는 재시작에서 살아남는다. 커서 리더의
 * {@code saveState} 기본값이 저장해 둔 위치만큼 앞을 건너뛰기 때문이며, 그래서 "우리 배치는
 * 재시작해도 멀쩡하던데요" 가 성립한다. 두 프로파일이 갈라지는 것은 <b>재시작이 아니라 재실행</b>
 * 에서이고, 그것은 각 프로파일의 하위 클래스가 확인한다.
 *
 * <p>재시작과 재실행을 가르는 것은 {@code run.id} 다. 같은 값을 다시 주면 같은 JobInstance 의
 * 재시작이고, 다른 값이면 새 인스턴스의 재실행이다. {@code failAfterCount} 는 <b>비식별</b>
 * 파라미터라 이 구분에 끼어들지 않는다 — 그래야 "장애만 빼고 똑같이 다시" 가 재시작으로 성립한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class RestartJobContract {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job restartJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected PointBalanceReporter balanceReporter;

    @Autowired
    protected DatabaseWorkloadListener workloadListener;

    /**
     * 이 테스트 메서드 전용 JobInstance 식별자.
     *
     * <p>JUnit 이 메서드마다 새 인스턴스를 만들므로 메서드마다 다른 값이 된다. 메타데이터 DB 는
     * 테스트 사이에 남아 있으므로, 값이 겹치면 앞선 메서드가 완료해 둔 인스턴스를 다시 실행하려다
     * 실패한다.
     */
    private final long runId = System.nanoTime();

    @BeforeEach
    void 데이터를_채운다() {
        RestartFixture.seed(jdbcTemplate);
    }

    /**
     * 테이블을 비우고 멱등키 제약도 되돌린다.
     *
     * <p>제약을 남기면 다음 테스트가 "before 인데 UK 가 있는" 상태에서 시작한다. Job 이 시작 시
     * 정리하기는 하지만, 뒤처리를 다음 실행에 미루지 않는다.
     */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("ALTER TABLE member_e DROP INDEX IF EXISTS uk_member_e_idem");
        jdbcTemplate.execute("TRUNCATE TABLE member_e");
    }

    @Test
    @DisplayName("활성 회원 전량을 소멸시키고 COMPLETED 로 끝난다")
    void 정상_1회_실행() throws Exception {
        JobExecution execution = launch(run());

        StepExecution step = step(execution);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(RestartFixture.activeCount());
        assertThat(step.getWriteCount())
                .as("프로세서가 null 을 돌려주지 않으므로 읽은 수와 쓴 수가 같다")
                .isEqualTo(RestartFixture.activeCount());
        assertThat(step.getFilterCount()).isZero();
        assertThat(pointSum()).isEqualTo(RestartFixture.expectedPointSum(1));
    }

    @Test
    @DisplayName("휴면 회원은 건드리지 않는다 - 대상 선별이 양쪽 같다는 전제")
    void 휴면_회원은_그대로다() throws Exception {
        launch(run());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_e WHERE status <> 'ACTIVE' AND updated_at IS NOT NULL", Long.class))
                .as("대상이 아닌 행이 갱신되면 대사식의 기준이 무너진다").isZero();
    }

    @Test
    @DisplayName("실패하면 커밋된 만큼만 반영된 채 멈춘다 - 재실행의 출발점")
    void 실패하면_커밋된_만큼만() throws Exception {
        JobExecution execution = launch(failing());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(execution).getWriteCount()).isEqualTo(RestartFixture.FAIL_AFTER);
        assertThat(pointSum())
                .as("실패한 청크는 위임 전에 던지므로 DB 에 흔적이 없다")
                .isEqualTo(RestartFixture.partialPointSum(RestartFixture.FAIL_AFTER));
    }

    @Test
    @DisplayName("실패해도 잔액 지문은 남는다 - 실패한 실행이야말로 무엇이 반영됐는지 알아야 한다")
    void 실패해도_지문이_남는다() throws Exception {
        launch(failing());

        assertThat(balanceReporter.afterChecksum().pointSum())
                .isEqualTo(RestartFixture.partialPointSum(RestartFixture.FAIL_AFTER));
        assertThat(balanceReporter.afterChecksum()
                .pointDelta(balanceReporter.beforeChecksum()))
                .isEqualTo(-RestartFixture.FAIL_AFTER * RestartJobCommonConfig.EXPIRE_AMOUNT);
    }

    @Test
    @DisplayName("같은 run.id 로 재시작하면 정확히 이어서 완주한다 - before 도 여기까지는 맞다")
    void 재시작하면_정확히_완주한다() throws Exception {
        JobExecution failed = launch(failing());
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);

        JobExecution restarted = launch(run());

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getJobInstance().getInstanceId())
                .as("식별 파라미터가 같으므로 새 인스턴스가 아니라 같은 인스턴스의 재시작이어야 한다")
                .isEqualTo(failed.getJobInstance().getInstanceId());
        assertThat(step(restarted).getWriteCount())
                .as("앞 %d 건은 다시 처리하지 않는다", RestartFixture.FAIL_AFTER)
                .isEqualTo(RestartFixture.activeCount() - RestartFixture.FAIL_AFTER);
        assertThat(pointSum())
                .as("한 번 실행한 것과 같은 결과여야 한다")
                .isEqualTo(RestartFixture.expectedPointSum(1));
    }

    @Test
    @DisplayName("member_e 가 비어 있으면 Step 에 진입하지 않는다 - 시딩을 잊은 실행과 멱등하게 끝난 실행은 다르다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE member_e");

        JobExecution execution = launch(run());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_e"));
    }

    @Test
    @DisplayName("Step 이름이 양쪽 같다 - 같은 축에서 비교하기 위한 전제")
    void step_이름() throws Exception {
        assertThat(step(launch(run())).getStepName()).isEqualTo("restartStep");
    }

    /**
     * Job 을 실행한다.
     *
     * @param parameters Job 파라미터
     * @return 실행 결과
     */
    protected JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(restartJob, parameters);
    }

    /**
     * 이 테스트의 JobInstance 를 가리키는 파라미터. 두 번 주면 <b>재시작</b>이다.
     *
     * @return 파라미터
     */
    protected JobParameters run() {
        return run(runId);
    }

    /**
     * 다음 회차의 JobInstance 를 가리키는 파라미터. {@link #run()} 과 다른 인스턴스이므로
     * <b>재실행</b>이 된다.
     *
     * @return 파라미터
     */
    protected JobParameters nextRun() {
        return run(runId + 1);
    }

    /**
     * 장애를 심은 실행 파라미터.
     *
     * <p>{@code failAfterCount} 를 <b>비식별</b>로 넘기는 것이 핵심이다. 식별 파라미터로 넘기면
     * 이 실행이 별도의 JobInstance 가 되어, 뒤이은 {@link #run()} 이 재시작이 아니라 새 실행이 된다.
     *
     * @return 파라미터
     */
    protected JobParameters failing() {
        return new JobParametersBuilder(run())
                .addLong("failAfterCount", RestartFixture.FAIL_AFTER, false)
                .toJobParameters();
    }

    /**
     * 현재 포인트 총합.
     *
     * @return 총합
     */
    protected long pointSum() {
        return balanceReporter.current().pointSum();
    }

    /**
     * 실행의 유일한 Step.
     *
     * @param execution 실행 결과
     * @return Step 실행
     */
    protected static StepExecution step(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }

    private static JobParameters run(long id) {
        return new JobParametersBuilder().addLong("run.id", id).toJobParameters();
    }
}
