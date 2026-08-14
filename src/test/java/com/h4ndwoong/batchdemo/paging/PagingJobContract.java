package com.h4ndwoong.batchdemo.paging;

import com.h4ndwoong.batchdemo.domain.MemberC;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.MemberTableSeeder;
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
 * {@code pagingJob} 의 before/after 가 <b>똑같이 만족해야 하는</b> 결과.
 *
 * <p>3번 문제에서 두 프로파일을 비교할 수 있으려면 먼저 <b>같은 일을 했다</b>는 것이 서야 한다.
 * 여기 있는 시험이 전부 그 확인이다 — 같은 건수를 읽고, 같은 행 집합을 순회하고, 같은 수의
 * 페이지를 가져오고, DB 를 건드리지 않는다. 이것이 양쪽에서 통과해야 "그런데 한쪽이 훨씬 느리다"
 * 가 의미를 갖는다.
 *
 * <p>서로 다른 것(스캔한 행 수)은 각 프로파일의 하위 클래스가 확인한다.
 *
 * <p>두 프로파일을 한 컨텍스트에 담을 수 없어 한 테스트에서 직접 대조하지 못하므로,
 * 양쪽이 {@link PagingFixture} 의 같은 상수와 대조하게 해서 같음을 보장한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
abstract class PagingJobContract {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job pagingJob;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TraversalChecksumItemWriter checksumWriter;

    @Autowired
    protected PageTimingRecorder pageTimingRecorder;

    @Autowired
    protected DatabaseWorkloadListener workloadListener;

    @BeforeEach
    void 데이터를_채운다() {
        MemberTableSeeder.seed(jdbcTemplate, "member_c", MemberC::new, PagingFixture.COUNT, 0);
    }

    /**
     * 테이블을 비우고 끝낸다. {@code seedJob} 은 대상 테이블이 비어 있어야 시작하므로, 데이터를
     * 남기면 {@code SeedJobTest} 가 이 테스트의 뒤처리를 대신 하게 된다.
     */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_c");
    }

    @Test
    @DisplayName("전량을 순회하고 COMPLETED 로 끝난다")
    void 전량_순회() throws Exception {
        JobExecution execution = launch(parameters());

        StepExecution step = step(execution);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(PagingFixture.COUNT);
        assertThat(step.getWriteCount())
                .as("가공이 없으므로 읽은 수와 쓴 수가 같다").isEqualTo(PagingFixture.COUNT);
        assertThat(step.getCommitCount())
                .as("청크 %d개 + 1", PagingFixture.COUNT / PagingJobCommonConfig.CHUNK_SIZE)
                .isEqualTo(PagingFixture.COUNT / PagingJobCommonConfig.CHUNK_SIZE + 1);
    }

    @Test
    @DisplayName("순회한 행 집합의 지문이 before/after 공통 기대값과 같다 - 비교의 전제")
    void 체크섬_일치() throws Exception {
        launch(parameters());

        assertThat(checksumWriter.checksum())
                .as("건수·범위·합이 모두 같아야 '같은 일을 더 빨리 했다' 가 성립한다")
                .isEqualTo(PagingFixture.CHECKSUM);
    }

    @Test
    @DisplayName("페이지 수가 양쪽 같다 - 쿼리 횟수는 이 문제의 차이가 아니다")
    void 페이지_수() throws Exception {
        launch(parameters());

        assertThat(pageTimingRecorder.report().pageCount()).isEqualTo(PagingFixture.PAGE_COUNT);
    }

    @Test
    @DisplayName("쿼리 왕복 횟수는 페이지 수 수준에 머문다 - 1번 문제와 달리 왕복은 줄지 않는다")
    void 쿼리_수() throws Exception {
        launch(parameters());

        assertThat(workloadListener.lastDelta().get("COM_SELECT"))
                .as("페이지 %d장 + 배치 메타데이터. 두 프로파일이 같은 수준이어야 한다",
                        PagingFixture.PAGE_COUNT)
                .isBetween((long) PagingFixture.PAGE_COUNT, 300L);
    }

    @Test
    @DisplayName("pages 파라미터를 주면 그만큼만 읽는다 - 긴 실행을 앞 구간으로 잘라 비교한다")
    void 일부만_순회() throws Exception {
        JobExecution execution = launch(new JobParametersBuilder(parameters())
                .addString("pages", String.valueOf(PagingFixture.PARTIAL_PAGES))
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution).getReadCount())
                .isEqualTo((long) PagingFixture.PARTIAL_PAGES * PagingJobCommonConfig.PAGE_SIZE);
        assertThat(pageTimingRecorder.report().pageCount())
                .as("상한에 걸리면 다음 페이지를 가져오지 않는다").isEqualTo(PagingFixture.PARTIAL_PAGES);
    }

    @Test
    @DisplayName("DB 에 아무것도 쓰지 않는다 - 읽기 경로만 측정하기 위한 전제")
    void 쓰지_않는다() throws Exception {
        launch(parameters());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_c WHERE processed = 1 OR updated_at IS NOT NULL", Long.class))
                .as("쓰기 비용이 섞이면 페이지 획득 시간의 차이가 묻힌다").isZero();
    }

    @Test
    @DisplayName("member_c 가 비어 있으면 Step 에 진입하지 않는다 - 0건 순회를 개선으로 읽지 않는다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE member_c");

        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_c"));
    }

    @Test
    @DisplayName("Step 이름이 양쪽 같다 - 같은 축에서 비교하기 위한 전제")
    void step_이름() throws Exception {
        JobExecution execution = launch(parameters());

        assertThat(step(execution).getStepName()).isEqualTo("pagingStep");
    }

    /**
     * Job 을 실행한다.
     *
     * @param parameters Job 파라미터
     * @return 실행 결과
     */
    protected JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(pagingJob, parameters);
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
     * 이번 Job 이 인덱스 순서로 읽은 행 수. 3번 문제의 주 지표다.
     *
     * @return 행 수
     */
    protected long rowsScanned() {
        return workloadListener.lastDelta().getOrDefault(DatabaseWorkloadListener.ROWS_SCANNED, 0L);
    }
}
