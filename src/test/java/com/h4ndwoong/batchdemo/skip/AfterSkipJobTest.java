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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2번 문제 after 구성({@link AfterSkipJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>여기서 지켜야 하는 것은 "끝까지 갔다" 가 아니라 <b>대사(reconciliation)가 맞는다</b>는 것이다.
 * <pre>
 *   읽은 수 = 쓴 수 + 스킵 수
 *   스킵 수 = 격리 테이블의 행 수
 *   처리되지 않은 행 = 오염 행, 그것도 정확히 그 행들
 * </pre>
 * 이 등식이 깨지면 after 는 "오류를 견디는 배치" 가 아니라 "데이터를 조용히 잃는 배치" 다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
@ActiveProfiles("after")
class AfterSkipJobTest {

    private static final long COUNT = 1_000L;
    private static final int CORRUPT_INTERVAL = 200;
    private static final List<Long> CORRUPT_IDS = MemberTableSeeder.corruptIds(COUNT, CORRUPT_INTERVAL);
    private static final long CORRUPT_COUNT = CORRUPT_IDS.size();

    /** 오염 행이 없는 청크의 첫 행. 재시도 실습이 스킵 실습과 섞이지 않게 이 청크에 장애를 심는다. */
    private static final long CLEAN_CHUNK_FIRST_ID = 401L;

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
    @DisplayName("오염 행을 건너뛰고 COMPLETED 로 끝난다 - 읽은 수 = 쓴 수 + 스킵 수")
    void 스킵하고_완주() throws Exception {
        JobExecution execution = launch(parameters());

        StepExecution step = step(execution);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isEqualTo(COUNT);
        assertThat(step.getWriteCount()).isEqualTo(COUNT - CORRUPT_COUNT);
        assertThat(step.getProcessSkipCount()).isEqualTo(CORRUPT_COUNT);
        assertThat(step.getReadCount())
                .as("대사식이 맞아야 한다").isEqualTo(step.getWriteCount() + step.getSkipCount());
    }

    @Test
    @DisplayName("스킵된 행이 격리 테이블에 원인과 함께 남는다 - 스킵만으로는 개선이 아니다")
    void 격리_적재() throws Exception {
        JobExecution execution = launch(parameters());

        assertThat(errorMemberIds())
                .as("빠진 행이 어느 것인지 알 수 있어야 사후 재처리가 가능하다")
                .containsExactlyElementsOf(CORRUPT_IDS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b_error WHERE phase = 'PROCESS'", Long.class))
                .isEqualTo(step(execution).getProcessSkipCount());
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT step_execution_id FROM member_b_error", Long.class))
                .containsExactly(step(execution).getId());
    }

    @Test
    @DisplayName("격리 기록의 원인 분포가 시드 데이터의 오염 분포와 일치한다")
    void 원인_분포() throws Exception {
        launch(parameters());

        assertThat(errorCountByRule(ValidationRule.EMAIL_FORMAT))
                .as("200, 600, 1000 번 행").isEqualTo(3L);
        assertThat(errorCountByRule(ValidationRule.NEGATIVE_POINT))
                .as("400, 800 번 행").isEqualTo(2L);
    }

    @Test
    @DisplayName("처리되지 않은 행은 오염 행뿐이다 - 조용한 유실이 없다")
    void 유실_없음() throws Exception {
        launch(parameters());

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM member_b WHERE processed = 0 ORDER BY id", Long.class))
                .containsExactlyElementsOf(CORRUPT_IDS);
        assertThat(processedCount()).isEqualTo(COUNT - CORRUPT_COUNT);
    }

    @Test
    @DisplayName("오염 행이 섞인 청크의 나머지 행은 정상 처리된다 - 격리 기록이 롤백에 휩쓸리지 않는다")
    void 롤백_이후에도_살아남는다() throws Exception {
        launch(parameters());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE processed = 1 AND id BETWEEN 101 AND 199", Long.class))
                .as("101~200 청크는 200번 행 때문에 롤백된 뒤 행 단위로 다시 처리된다").isEqualTo(99L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b_error WHERE member_id = 200", Long.class))
                .as("그 롤백에도 불구하고 격리 기록은 남는다").isEqualTo(1L);
    }

    @Test
    @DisplayName("스킵의 대가는 커밋 폭증이 아니라 청크 롤백과 재처리다 - 실측으로 확인한 값")
    void 스킵_비용() throws Exception {
        JobExecution execution = launch(parameters());

        StepExecution step = step(execution);
        long chunkCount = COUNT / SkipJobCommonConfig.CHUNK_SIZE;

        assertThat(step.getRollbackCount())
                .as("가공 단계 스킵은 청크를 롤백시킨다. 오염 1건 = 롤백 1회")
                .isEqualTo(CORRUPT_COUNT);
        assertThat(step.getCommitCount())
                .as("롤백된 청크는 다시 처리되어 결국 한 번 커밋된다. 커밋 횟수는 늘지 않는다")
                .isEqualTo(chunkCount + 1);
        assertThat(step.getReadCount())
                .as("재처리는 캐시된 청크로 하므로 리더를 다시 부르지 않는다. 읽은 수는 건수 그대로다")
                .isEqualTo(COUNT);
        assertThat(step.getFilterCount())
                .as("오염 행은 필터가 아니라 스킵으로 집계되어야 한다").isZero();
    }

    @Test
    @DisplayName("일시 장애는 스킵하지 않고 재시도로 넘긴다 - 멀쩡한 행을 버리지 않는다")
    void 일시_장애_재시도() throws Exception {
        JobExecution execution = launch(faultParameters(FaultKind.TRANSIENT, 2));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE processed = 1 AND id BETWEEN 401 AND 500", Long.class))
                .as("두 번 실패한 청크도 재시도로 끝내 반영된다").isEqualTo(100L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b_error WHERE phase = 'WRITE'", Long.class))
                .as("일시 장애는 격리 대상이 아니다. 격리했다면 정상 행을 버린 것이다").isZero();
        assertThat(errorMemberIds()).containsExactlyElementsOf(CORRUPT_IDS);
    }

    @Test
    @DisplayName("재시도가 소진되면 Step 은 실패한다 - 무한히 견디지 않는다")
    void 재시도_소진() throws Exception {
        JobExecution execution = launch(faultParameters(FaultKind.TRANSIENT, AfterSkipJobConfig.RETRY_LIMIT + 2));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE processed = 1 AND id BETWEEN 401 AND 500", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("분류되지 않은 예외는 스킵되지 않고 Step 을 실패시킨다 - 코드 버그를 삼키지 않는다")
    void 치명적_예외는_실패() throws Exception {
        JobExecution execution = launch(faultParameters(FaultKind.FATAL, 1));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .as("Spring Batch 는 쓰기 예외를 재시도 템플릿으로 감싸므로 "
                        + "재시도 대상도 스킵 대상도 아닌 예외는 ExhaustedRetryException 에 담겨 올라온다")
                .anySatisfy(exception -> assertThat(exception)
                        .hasRootCauseInstanceOf(IllegalStateException.class));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b_error WHERE phase = 'WRITE'", Long.class))
                .as("스킵 목록에 없으므로 격리 대상도 아니다").isZero();
    }

    @Test
    @DisplayName("스킵 상한을 넘으면 Step 은 실패한다 - 데이터 소스가 깨진 것은 다른 문제다")
    void 스킵_상한_초과() throws Exception {
        long count = (AfterSkipJobConfig.SKIP_LIMIT + 500L) * 2;
        MemberTableSeeder.seed(jdbcTemplate, "member_b", MemberB::new, count, 2);

        JobExecution execution = launch(parameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(execution).getSkipCount())
                .as("상한을 넘는 순간 멈춘다. 무제한 스킵은 '전부 실패해도 성공' 과 같은 말이다")
                .isLessThanOrEqualTo(AfterSkipJobConfig.SKIP_LIMIT + 1L);
    }

    @Test
    @DisplayName("재실행해도 최종 상태가 같다 - 격리 기록만 실행 단위로 쌓인다")
    void 재실행_멱등() throws Exception {
        JobExecution first = launch(parameters());
        JobExecution second = launch(parameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(processedCount()).isEqualTo(COUNT - CORRUPT_COUNT);
        assertThat(errorRowCount())
                .as("두 실행의 기록이 누적된다").isEqualTo(CORRUPT_COUNT * 2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT step_execution_id FROM member_b_error ORDER BY 1", Long.class))
                .as("step_execution_id 로 어느 실행의 기록인지 구분된다")
                .containsExactly(step(first).getId(), step(second).getId());
    }

    @Test
    @DisplayName("Step 이름이 before 와 같다 - 같은 축에서 비교하기 위한 전제")
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

    private JobParameters faultParameters(FaultKind kind, int times) {
        return new JobParametersBuilder(parameters())
                .addString("faultAtId", String.valueOf(CLEAN_CHUNK_FIRST_ID))
                .addString("faultTimes", String.valueOf(times))
                .addString("faultKind", kind.name())
                .toJobParameters();
    }

    private StepExecution step(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }

    private List<Long> errorMemberIds() {
        return jdbcTemplate.queryForList(
                "SELECT member_id FROM member_b_error ORDER BY member_id", Long.class);
    }

    private Long errorCountByRule(ValidationRule rule) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b_error WHERE message LIKE ?", Long.class, rule.name() + "%");
    }

    private Long processedCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_b WHERE processed = 1", Long.class);
    }

    private Long errorRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_b_error", Long.class);
    }
}
