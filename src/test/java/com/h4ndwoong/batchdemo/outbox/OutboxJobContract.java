package com.h4ndwoong.batchdemo.outbox;

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
 * {@code outboxJob} 의 before/after 가 <b>똑같이 만족해야 하는</b> 결과.
 *
 * <p>여기 있는 시험은 전부 "두 프로파일이 DB 에 대해 같은 일을 한다" 의 확인이다 — 같은 회원을
 * 고르고, 같은 상태로 바꾸고, 건드리지 말아야 할 행을 건드리지 않고, 재실행하면 아무것도 하지
 * 않는다. <b>7번에서 이 계약이 통과한다는 사실 자체가 문제의 핵심이다.</b>
 *
 * <blockquote>
 * DB 만 보면 before 는 아무 문제가 없다. 증상은 DB 에 적히지 않는 곳에 있다.
 * </blockquote>
 *
 * <p>1~6번에서 계약 시험은 "개선이 결과를 망치지 않았다" 를 보이는 안전장치였다. 7번에서는 그
 * 역할에 더해 <b>"before 가 왜 발견되지 않는가" 의 설명</b>이 된다. 상태 분포도, 갱신 행 수도,
 * 멱등성도 전부 통과하는 배치가 사람들에게 같은 문자를 두 번 보내고 있다.
 *
 * <p>두 프로파일이 갈라지는 지점(유령 알림, 중복 발송, Outbox 적재)은 각 하위 클래스가 확인한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        OutboxFixture.SENDER_LOG_LEVEL
})
abstract class OutboxJobContract {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job outboxJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected NotificationRecorder recorder;

    @Autowired
    protected NotificationDeliveryReporter reporter;

    @Autowired
    protected DatabaseWorkloadListener workloadListener;

    /**
     * 이 테스트 메서드 전용 JobInstance 식별자.
     *
     * <p>메타데이터 DB 는 테스트 사이에 남으므로, 값이 겹치면 앞선 메서드가 완료해 둔 인스턴스를
     * 다시 실행하려다 실패한다 (5·6번 계약과 같은 장치다).
     */
    private final long runId = System.nanoTime();

    /**
     * 데이터를 채우고 <b>발송 기록을 비운다.</b>
     *
     * <p>기록기를 여기서 비우는 것이 7번의 특징이다. 6번의 슬라이스 기록기는 Step 이 시작할 때마다
     * 스스로 비웠지만, 이 기록기는 <b>한 테스트 메서드 안의 여러 실행을 가로질러 누적해야 한다</b> —
     * 중복 발송은 그래야만 보인다.
     */
    @BeforeEach
    void 데이터를_채운다() {
        OutboxFixture.seed(jdbcTemplate);
        recorder.reset();
    }

    /** 실습 테이블과 Outbox 를 함께 비운다. 한쪽만 비우면 다음 테스트가 남은 발송 요청을 물려받는다. */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.TABLE);
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.OUTBOX_TABLE);
    }

    @Test
    @DisplayName("대상 전원의 상태를 바꾸고 정확히 한 번씩 알림을 보낸다")
    void 정상_1회_실행() throws Exception {
        JobExecution execution = launch(run());

        NotificationDeliveryChecksum checksum = reporter.current();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(checksum.activeRows()).as("대상이 남아 있으면 안 된다").isZero();
        assertThat(checksum.changedRows()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.sendAttempts()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.distinctKeys()).isEqualTo(OutboxFixture.targetCount());
        assertThat(checksum.duplicateSends()).isZero();
        assertThat(checksum.phantomSends())
                .as("정상 실행에서는 어느 프로파일도 유령 알림을 만들지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("나간 알림은 대상 회원의 멱등키와 정확히 일치한다")
    void 발송된_키가_규칙대로다() throws Exception {
        launch(run());

        assertThat(recorder.keys())
                .as("빠진 회원도, 대상이 아닌 회원도 없어야 한다")
                .isEqualTo(OutboxFixture.expectedKeys());
    }

    @Test
    @DisplayName("이미 휴면인 회원은 건드리지도 알리지도 않는다")
    void 대상이_아닌_행은_그대로다() throws Exception {
        launch(run());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_g WHERE status = 'DORMANT' AND updated_at IS NULL",
                Long.class))
                .as("시드의 휴면 회원 %d 건은 손대지 않은 채로 남아야 한다", OutboxFixture.seededDormantCount())
                .isEqualTo(OutboxFixture.seededDormantCount());
        assertThat(recorder.attemptCount())
                .as("대상이 아닌 회원에게 알림이 갔다면 시도 수가 대상 수보다 많다")
                .isEqualTo(OutboxFixture.targetCount());
    }

    @Test
    @DisplayName("재실행해도 상태를 바꾸지 않고 알림도 보내지 않는다 - DB 쪽 멱등성은 양쪽 다 옳다")
    void 재실행은_아무것도_하지_않는다() throws Exception {
        launch(run());
        long afterFirst = recorder.attemptCount();

        JobExecution second = launch(nextRun());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(recorder.attemptCount())
                .as("읽기 조건이 자기가 쓰는 컬럼을 보므로 재실행에는 대상이 없다 (5번 after 와 같다)")
                .isEqualTo(afterFirst);
        assertThat(reporter.current().changedRows()).isEqualTo(OutboxFixture.targetCount());
    }

    @Test
    @DisplayName("커밋 직전에 죽으면 그 앞까지만 커밋된다 - DB 관점에서는 양쪽이 같다")
    void 실패한_실행은_커밋된_것만_남긴다() throws Exception {
        JobExecution execution = launch(runWithFailure());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reporter.current().changedRows())
                .as("실패한 청크의 상태 변경은 롤백된다. 되돌아오는 것은 여기까지다")
                .isEqualTo(OutboxFixture.FAIL_AFTER);
    }

    @Test
    @DisplayName("member_g 가 비어 있으면 Step 에 진입하지 않는다 - 0건 성공은 개선과 구분되지 않는다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE " + OutboxJobCommonConfig.TABLE);

        JobExecution execution = launch(run());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_g"));
    }

    @Test
    @DisplayName("Step 이름이 양쪽 같다 - 메타데이터를 한 축에서 읽기 위한 전제")
    void step_이름() throws Exception {
        assertThat(step(launch(run()), "outboxStep").getStepName()).isEqualTo("outboxStep");
    }

    /**
     * Job 을 실행한다.
     *
     * @param parameters Job 파라미터
     * @return 실행 결과
     */
    protected JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(outboxJob, parameters);
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
     * 장애를 심은 파라미터. {@code failAfterCount} 는 <b>비식별</b>이다.
     *
     * @return 파라미터
     */
    protected JobParameters runWithFailure() {
        return new JobParametersBuilder()
                .addLong("run.id", runId)
                .addLong("failAfterCount", OutboxFixture.FAIL_AFTER, false)
                .toJobParameters();
    }

    /**
     * 다음 회차의 JobInstance 를 가리키는 파라미터. 재시작이 아니라 <b>재실행</b>이 된다.
     *
     * @return 파라미터
     */
    protected JobParameters nextRun() {
        return new JobParametersBuilder().addLong("run.id", runId + 1).toJobParameters();
    }

    /**
     * 이름으로 Step 실행을 찾는다. after 는 Step 이 둘이므로 순서로 집지 않는다.
     *
     * @param execution 실행 결과
     * @param stepName  Step 이름
     * @return Step 실행
     */
    protected StepExecution step(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step 이 실행되지 않았다: " + stepName));
    }
}
