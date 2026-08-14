package com.h4ndwoong.batchdemo.skip;

import com.h4ndwoong.batchdemo.domain.MemberB;
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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2번 문제 before 구성({@link BeforeSkipJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>검증 대상은 <b>증상이 재현되는가</b>다. 10만 건 실측은 {@code bootRun} 의 몫이고, 여기서는
 * 1000건으로 "오염 행 하나에 Step 전체가 죽는가", "그때 얼마나 처리된 채로 멈추는가",
 * "어느 행이 문제였는지 아무 데도 남지 않는가" 를 본다.
 *
 * <p>오염 간격이 200이고 청크가 {@value SkipJobCommonConfig#CHUNK_SIZE} 이므로 실패 지점은 항상
 * 같다. 첫 청크(1~100)는 커밋되고 두 번째 청크(101~200)의 마지막 행에서 죽는다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
@ActiveProfiles("before")
class BeforeSkipJobTest {

    private static final long COUNT = 1_000L;
    private static final int CORRUPT_INTERVAL = 200;

    /** 첫 오염 행의 식별자. 두 번째 청크의 마지막 행이다. */
    private static final long FIRST_CORRUPT_ID = CORRUPT_INTERVAL;

    /** 죽기 전에 커밋되는 건수. 첫 청크 하나뿐이다. */
    private static final long COMMITTED_BEFORE_FAILURE = SkipJobCommonConfig.CHUNK_SIZE;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job skipJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 오염된_데이터로_되돌린다() {
        MemberTableSeeder.seed(jdbcTemplate, "member_b", MemberB::new, COUNT, CORRUPT_INTERVAL);
        jdbcTemplate.execute("TRUNCATE TABLE member_b_error");
    }

    /**
     * 테이블을 비우고 끝낸다. {@code seedJob} 은 대상 테이블이 비어 있어야 시작하므로, 데이터를
     * 남기면 {@code SeedJobTest} 가 이 테스트의 뒤처리를 대신 하게 된다.
     */
    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_b");
        jdbcTemplate.execute("TRUNCATE TABLE member_b_error");
    }

    @Test
    @DisplayName("오염 행 1건에 Step 전체가 FAILED 로 끝난다 - before 의 핵심 증상")
    void 오염_1건에_전체_실패() throws Exception {
        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(execution).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .as("실패 원인은 검증 위반이다")
                .anySatisfy(exception -> assertThat(exception).isInstanceOf(MemberValidationException.class));
    }

    @Test
    @DisplayName("1000건 중 100건만 반영된 채 멈춘다 - 어디까지 처리됐는지는 세어 봐야 안다")
    void 부분_처리() throws Exception {
        JobExecution execution = launch(parameters());

        StepExecution step = step(execution);
        assertThat(step.getReadCount())
                .as("실패한 청크까지 읽는다").isEqualTo(FIRST_CORRUPT_ID);
        assertThat(step.getWriteCount()).isEqualTo(COMMITTED_BEFORE_FAILURE);
        assertThat(step.getSkipCount()).as("before 는 스킵이라는 개념 자체가 없다").isZero();

        assertThat(processedCount()).isEqualTo(COMMITTED_BEFORE_FAILURE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE processed = 1 AND id <= ?", Long.class,
                COMMITTED_BEFORE_FAILURE))
                .as("반영된 것은 첫 청크의 1~100 뿐이다").isEqualTo(COMMITTED_BEFORE_FAILURE);
    }

    @Test
    @DisplayName("오염 행이 어느 것이었는지 아무 데도 남지 않는다")
    void 격리_없음() throws Exception {
        launch(parameters());

        assertThat(errorRowCount())
                .as("before 에는 격리 장치가 없다. 로그의 스택트레이스에 첫 행 하나만 남는다").isZero();
    }

    @Test
    @DisplayName("재실행해도 같은 지점에서 다시 죽는다 - 사람이 데이터를 고치기 전에는 끝나지 않는다")
    void 재실행해도_같은_실패() throws Exception {
        launch(parameters());
        JobExecution second = launch(parameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(second).getReadCount()).isEqualTo(FIRST_CORRUPT_ID);
        assertThat(processedCount())
                .as("두 번째 실행도 같은 100건을 다시 쓰고 같은 자리에서 멈춘다")
                .isEqualTo(COMMITTED_BEFORE_FAILURE);
    }

    @Test
    @DisplayName("오염이 없으면 같은 구성으로 전 건이 처리된다 - 실패 원인이 데이터임을 고정한다")
    void 깨끗한_데이터() throws Exception {
        MemberTableSeeder.seed(jdbcTemplate, "member_b", MemberB::new, COUNT, 0);

        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(execution).getWriteCount()).isEqualTo(COUNT);
        assertThat(processedCount()).isEqualTo(COUNT);
    }

    @Test
    @DisplayName("일시 장애도 그대로 실패한다 - before 는 재시도하지 않는다")
    void 일시_장애도_실패() throws Exception {
        MemberTableSeeder.seed(jdbcTemplate, "member_b", MemberB::new, COUNT, 0);

        JobExecution execution = launch(new JobParametersBuilder(parameters())
                .addString("faultAtId", "401")
                .addString("faultTimes", "1")
                .toJobParameters());

        assertThat(execution.getStatus())
                .as("한 번만 흔들려도 죽는다. after 는 이것을 재시도로 넘긴다").isEqualTo(BatchStatus.FAILED);
        assertThat(processedCount()).isEqualTo(400L);
    }

    @Test
    @DisplayName("member_b 가 비어 있으면 Step 에 진입하지 않는다")
    void 빈_테이블() throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE member_b");

        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_b"));
    }

    @Test
    @DisplayName("Step 이름이 after 와 같다 - 같은 축에서 비교하기 위한 전제")
    void step_이름() throws Exception {
        JobExecution execution = launch(parameters());

        assertThat(step(execution).getStepName()).isEqualTo("skipStep");
    }

    private JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(skipJob, parameters);
    }

    private JobParameters parameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    private StepExecution step(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }

    private Long processedCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_b WHERE processed = 1", Long.class);
    }

    private Long errorRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_b_error", Long.class);
    }
}
