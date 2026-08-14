package com.h4ndwoong.batchdemo.seed;

import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
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
 * {@code seedJob} 을 실제 MariaDB 에 실행해 적재 결과를 검증한다.
 *
 * <p>실습 DB 가 아닌 {@link TestDatabase} 의 별도 DB 를 쓴다. 실행마다 남는 Job 메타데이터가
 * before/after 비교 관측을 방해하지 않게 하기 위함이다.
 *
 * <p><b>{@code @SpringBatchTest} 를 쓰지 않는 이유</b><br>
 * 이 애노테이션이 등록하는 {@code JobScopeTestExecutionListener} 는 테스트 클래스에서
 * {@code JobExecution} 을 반환하는 <em>아무</em> 메서드나 찾아 인자 없이 호출한다. 그래서
 * {@code launch(JobParameters)} 같은 헬퍼가 있으면 그걸 붙잡고 실패한다. 여기서는 job 스코프
 * 빈을 테스트에 직접 주입할 필요가 없으므로, {@link JobLauncher} 로 실제 실행 경로와 똑같이
 * 실행한다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
class SeedJobTest {

    private static final long COUNT = 1_000L;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job seedJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 대상_테이블_정리() {
        jdbcTemplate.execute("TRUNCATE TABLE member_b");
        jdbcTemplate.execute("TRUNCATE TABLE member_d");
    }

    @Test
    @DisplayName("지정한 건수를 대상 테이블에 적재하고 COMPLETED 로 끝난다")
    void 시딩_성공() throws Exception {
        JobExecution execution = launch(parameters("member_b", COUNT));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rowCount("member_b")).isEqualTo(COUNT);

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getStepName()).isEqualTo("seedStep");
        assertThat(stepExecution.getReadCount()).isEqualTo(COUNT);
        assertThat(stepExecution.getWriteCount()).isEqualTo(COUNT);
    }

    @Test
    @DisplayName("적재된 행의 컬럼 값이 생성기가 만든 값과 일치한다")
    void 적재된_값_대조() throws Exception {
        launch(parameters("member_b", COUNT));

        MemberSeedGenerator generator = MemberSeedGenerator.forTarget(
                SeedTarget.MEMBER_B, MemberSeedGenerator.DEFAULT_SEED, MemberSeedGenerator.BASE_TIME);
        var expected = generator.generate(7L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT email FROM member_b WHERE id = 7", String.class))
                .isEqualTo(expected.getEmail());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grade FROM member_b WHERE id = 7", String.class))
                .isEqualTo(expected.getGrade().name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point FROM member_b WHERE id = 7", Long.class))
                .isEqualTo(expected.getPoint());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processed FROM member_b WHERE id = 7", Boolean.class))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE updated_at IS NOT NULL OR idempotency_key IS NOT NULL",
                Long.class))
                .as("시딩 직후에는 아직 아무 행도 처리되지 않았다").isZero();
    }

    @Test
    @DisplayName("member_b 에는 이메일 형식 오류와 음수 포인트가 섞여 적재된다 - 2번 문제")
    void 오염_행_적재() throws Exception {
        launch(parameters("member_b", COUNT));

        Long invalidEmails = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE email NOT LIKE '%@%'", Long.class);
        Long negativePoints = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_b WHERE point < 0", Long.class);

        assertThat(invalidEmails).isEqualTo(3L);
        assertThat(negativePoints).isEqualTo(2L);
        assertThat(invalidEmails + negativePoints)
                .as("오염 간격 200 이면 1000건 중 5건이 오염된다")
                .isEqualTo(COUNT / SeedTarget.MEMBER_B.corruptInterval());
    }

    @Test
    @DisplayName("member_d 의 referrer_id 는 모두 실제 존재하는 행을 가리킨다 - 4번 문제")
    void 자기_참조_무결성() throws Exception {
        launch(parameters("member_d", COUNT));

        Long dangling = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM member_d m
                LEFT JOIN member_d r ON m.referrer_id = r.id
                WHERE m.referrer_id IS NOT NULL AND r.id IS NULL""", Long.class);
        Long withoutReferrer = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_d WHERE referrer_id IS NULL", Long.class);

        assertThat(dangling).as("FK 가 없으므로 무결성은 생성기가 보장해야 한다").isZero();
        assertThat(withoutReferrer).as("첫 행만 추천인이 없다").isEqualTo(1L);
    }

    @Test
    @DisplayName("대상 테이블이 비어 있지 않으면 시작하지 않는다")
    void 중복_시딩_차단() throws Exception {
        launch(parameters("member_b", COUNT));

        JobExecution second = launch(parameters("member_b", COUNT));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getStepExecutions()).as("Step 에 진입하지 않는다").isEmpty();
        assertThat(second.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_b"));
        assertThat(rowCount("member_b")).as("두 번째 실행은 아무것도 쓰지 않는다").isEqualTo(COUNT);
    }

    @Test
    @DisplayName("id 는 1부터 건수까지 구멍 없이 채워진다 - 순번과 id 가 일치한다는 보장")
    void 식별자는_순번과_같다() throws Exception {
        launch(parameters("member_b", COUNT));

        assertThat(jdbcTemplate.queryForObject("SELECT MIN(id) FROM member_b", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT MAX(id) FROM member_b", Long.class)).isEqualTo(COUNT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT id) FROM member_b", Long.class))
                .as("AUTO_INCREMENT 에 맡기면 드라이버의 bulk INSERT 때문에 번호에 구멍이 생긴다")
                .isEqualTo(COUNT);
    }

    @Test
    @DisplayName("시딩할 수 없는 대상이면 실패한다")
    void 잘못된_대상() throws Exception {
        JobExecution execution = launch(new JobParametersBuilder()
                .addString("target", "member_a")
                .addLong("run.id", System.nanoTime())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("insertJob"));
    }

    private JobExecution launch(JobParametersBuilder builder) throws Exception {
        return launch(builder.toJobParameters());
    }

    private JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(seedJob, parameters);
    }

    /**
     * Job 파라미터를 만든다. {@code run.id} 는 {@code RunIdIncrementer} 를 쓰지 않고 직접 넣는다.
     * 테스트를 반복 실행해도 같은 {@code JobInstance} 로 취급되지 않아야 하기 때문이다.
     */
    private JobParametersBuilder parameters(String target, long count) {
        return new JobParametersBuilder()
                .addString("target", target)
                .addString("count", String.valueOf(count))
                .addString("chunkSize", "100")
                .addLong("run.id", System.nanoTime());
    }

    private Long rowCount(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }
}
