package com.h4ndwoong.batchdemo.lookup;

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
 * {@code lookupJob} 의 before/after 가 <b>똑같이 만족해야 하는</b> 결과.
 *
 * <p>4번 문제에서 두 프로파일을 비교할 수 있으려면 먼저 <b>같은 답을 냈다</b>는 것이 서야 한다.
 * 여기 있는 시험이 전부 그 확인이다 — 같은 건수를 읽고, 같은 등급 산정 결과를 내고, 같은 횟수의
 * 조회를 <em>요구</em>하고, DB 를 건드리지 않는다. 이것이 양쪽에서 통과해야 "그런데 한쪽은 왕복이
 * 2,000배 많다" 가 의미를 갖는다.
 *
 * <p>서로 다른 것(왕복 횟수)은 각 프로파일의 하위 클래스가 확인한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class LookupJobContract {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job lookupJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected GradeDecisionItemWriter decisionWriter;

    @Autowired
    protected ReferrerLookup referrerLookup;

    @Autowired
    protected DatabaseWorkloadListener workloadListener;

    @Autowired
    protected LookupChunkSize chunkSize;

    @BeforeEach
    void 데이터를_채운다() {
        LookupFixture.seed(jdbcTemplate);
    }

    /**
     * 테이블을 비우고 끝낸다. {@code seedJob} 은 대상 테이블이 비어 있어야 시작하므로, 데이터를
     * 남기면 {@code SeedJobTest} 가 이 테스트의 뒤처리를 대신 하게 된다.
     */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_d");
    }

    @Test
    @DisplayName("전량을 가공하고 COMPLETED 로 끝난다")
    void 전량_가공() throws Exception {
        JobExecution execution = launch(parameters());

        StepExecution step = step(execution);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(LookupFixture.COUNT);
        assertThat(step.getWriteCount())
                .as("프로세서가 null 을 돌려주지 않으므로 읽은 수와 쓴 수가 같다")
                .isEqualTo(LookupFixture.COUNT);
        assertThat(step.getFilterCount())
                .as("필터로 새는 행이 있으면 READ = WRITE 대사가 깨진다").isZero();
    }

    @Test
    @DisplayName("등급 산정 결과가 before/after 공통 기대값과 같다 - 비교의 전제")
    void 산정_결과_일치() throws Exception {
        launch(parameters());

        assertThat(decisionWriter.checksum())
                .as("조회를 묶다가 엉뚱한 추천인을 붙이면 배치는 성공하고 등급만 조용히 틀린다")
                .isEqualTo(LookupFixture.checksum(LookupFixture.COUNT));
    }

    @Test
    @DisplayName("조회를 요구한 횟수가 양쪽 같다 - 프로세서는 똑같이 물었다")
    void 조회_요구_횟수() throws Exception {
        launch(parameters());

        assertThat(referrerLookup.stats().lookups())
                .as("추천인이 없는 %d행(id=1)은 조회하지 않는다", LookupFixture.ROWS_WITHOUT_REFERRER)
                .isEqualTo(LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER);
    }

    @Test
    @DisplayName("limit 파라미터를 주면 그만큼만 가공한다 - 긴 실행을 앞 구간으로 잘라 비교한다")
    void 일부만_가공() throws Exception {
        JobExecution execution = launch(new JobParametersBuilder(parameters())
                .addString("limit", String.valueOf(LookupFixture.PARTIAL_COUNT))
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution).getReadCount()).isEqualTo(LookupFixture.PARTIAL_COUNT);
        assertThat(decisionWriter.checksum())
                .as("정책은 테이블 전체에서 나오므로 일부만 처리해도 같은 등급이 나온다")
                .isEqualTo(LookupFixture.checksum(LookupFixture.PARTIAL_COUNT));
    }

    @Test
    @DisplayName("DB 에 아무것도 쓰지 않는다 - 조회 경로만 측정하기 위한 전제")
    void 쓰지_않는다() throws Exception {
        launch(parameters());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_d WHERE processed = 1 OR updated_at IS NOT NULL", Long.class))
                .as("행당 1회 UPDATE 가 얹히면 조회 왕복의 차이가 그 비용에 묻힌다").isZero();
        assertThat(workloadListener.lastDelta().get("COM_UPDATE"))
                .as("배치 메타데이터 갱신 말고는 UPDATE 가 없다")
                .isLessThan(3 * (LookupFixture.COUNT / chunkSize.value() + 5));
    }

    @Test
    @DisplayName("member_d 가 비어 있으면 Step 에 진입하지 않는다 - 조회 0회를 개선으로 읽지 않는다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE member_d");

        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_d"));
    }

    @Test
    @DisplayName("Step 이름이 양쪽 같다 - 같은 축에서 비교하기 위한 전제")
    void step_이름() throws Exception {
        JobExecution execution = launch(parameters());

        assertThat(step(execution).getStepName()).isEqualTo("lookupStep");
    }

    @Test
    @DisplayName("두 번 실행해도 계측치가 이어 붙지 않는다 - 측정 장치가 실행마다 초기화된다")
    void 계측치가_초기화된다() throws Exception {
        launch(parameters());
        long first = referrerLookup.stats().queries();

        launch(parameters());

        assertThat(referrerLookup.stats().queries())
                .as("두 번째 실행의 보고에 첫 실행이 섞이면 배율이 두 배로 보인다").isEqualTo(first);
    }

    /**
     * Job 을 실행한다.
     *
     * @param parameters Job 파라미터
     * @return 실행 결과
     */
    protected JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(lookupJob, parameters);
    }

    /**
     * 매 실행을 새 인스턴스로 만드는 파라미터.
     *
     * @return 파라미터
     */
    protected static JobParameters parameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
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

    /**
     * 이번 Job 이 보낸 SELECT 문 수. 배치 메타데이터 조회까지 포함한 서버 전역 값이다.
     *
     * @return SELECT 문 수
     */
    protected long serverSelects() {
        return workloadListener.lastDelta().getOrDefault("COM_SELECT", 0L);
    }
}
