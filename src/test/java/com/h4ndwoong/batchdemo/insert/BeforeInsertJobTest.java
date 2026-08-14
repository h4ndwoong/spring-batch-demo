package com.h4ndwoong.batchdemo.insert;

import com.h4ndwoong.batchdemo.domain.MemberA;
import com.h4ndwoong.batchdemo.domain.MemberBase;
import com.h4ndwoong.batchdemo.seed.MemberSeedGenerator;
import com.h4ndwoong.batchdemo.support.DatabaseWorkloadListener;
import com.h4ndwoong.batchdemo.support.TestDatabase;
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
 * 1번 문제 before 구성({@link BeforeInsertJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>검증 대상은 <b>성능이 아니라 계약</b>이다. 100만 건 실측은 {@code bootRun} 의 몫이고, 여기서는
 * 1000건으로 "인덱스가 적재 전에 존재하는가", "청크 수만큼 커밋하는가", "빈 테이블에만 적재하는가",
 * "적재된 값이 생성기와 일치하는가" 를 본다. 이 계약이 지켜져야 측정치가 before 를 대표한다.
 *
 * <p>실습 DB 가 아닌 {@link TestDatabase} 의 별도 DB 를 쓴다. 실행마다 남는 Job 메타데이터가
 * before/after 비교 관측을 방해하지 않게 하기 위함이다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
@ActiveProfiles("before")
class BeforeInsertJobTest {

    private static final long COUNT = 1_000L;
    private static final long EXPECTED_CHUNKS = COUNT / BeforeInsertJobConfig.CHUNK_SIZE;

    private static final List<String> SECONDARY_INDEXES =
            List.of("uk_member_a_email", "idx_member_a_grade", "idx_member_a_created_at");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job insertJob;

    @Autowired
    private IndexPreCreationListener indexPreCreationListener;

    @Autowired
    private DatabaseWorkloadListener workloadListener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * "인덱스 없는 빈 테이블" 에서 시작한다. 1번 문제의 before 는 인덱스를 <em>스스로</em> 만드는
     * 것이 증상의 일부이므로, 이전 테스트가 남긴 인덱스를 지우지 않으면 그 사실을 검증할 수 없다.
     */
    @BeforeEach
    void 인덱스_없는_빈_테이블로_되돌린다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_a");
        jdbcTemplate.execute("ALTER TABLE member_a DROP INDEX IF EXISTS uk_member_a_email");
        jdbcTemplate.execute("ALTER TABLE member_a DROP INDEX IF EXISTS idx_member_a_grade");
        jdbcTemplate.execute("ALTER TABLE member_a DROP INDEX IF EXISTS idx_member_a_created_at");
    }

    @Test
    @DisplayName("지정한 건수를 member_a 에 적재하고 COMPLETED 로 끝난다")
    void 적재_성공() throws Exception {
        JobExecution execution = launch(parameters(COUNT));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rowCount()).isEqualTo(COUNT);

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getStepName())
                .as("after 와 같은 이름이어야 같은 축에서 비교할 수 있다").isEqualTo("insertStep");
        assertThat(stepExecution.getReadCount()).isEqualTo(COUNT);
        assertThat(stepExecution.getWriteCount()).isEqualTo(COUNT);
    }

    @Test
    @DisplayName("chunk(100) 이므로 건수/100 만큼 커밋한다 - before 의 잦은 커밋")
    void 커밋_횟수() throws Exception {
        JobExecution execution = launch(parameters(COUNT));

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();

        assertThat(stepExecution.getCommitCount())
                .as("100만 건이면 이 값이 1만이 된다. after 는 chunk(5000) 으로 200번에 끝낸다")
                .isEqualTo(EXPECTED_CHUNKS + 1);
    }

    @Test
    @DisplayName("INSERT 문을 행 수만큼 보낸다 - before 의 행별 왕복")
    void 행별_왕복() throws Exception {
        JobExecution execution = launch(parameters(COUNT));

        long statements = workloadListener.lastDelta().get(DatabaseWorkloadListener.INSERT_STATEMENTS);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat((double) statements / COUNT)
                .as("IDENTITY 채번 때문에 JDBC batch 가 꺼져 행마다 왕복한다. "
                        + "여유 폭은 배치 메타데이터 INSERT 몫이다. after 는 이 값이 1 보다 훨씬 작아야 한다")
                .isBetween(1.0, 1.1);
    }

    @Test
    @DisplayName("보조 인덱스는 적재가 시작되기 전에 만들어진다 - before 의 핵심 증상")
    void 인덱스_선생성() {
        assertThat(secondaryIndexNames()).as("사전 조건: 인덱스 없이 시작한다").isEmpty();

        indexPreCreationListener.beforeJob(null);

        assertThat(secondaryIndexNames())
                .as("Job 시작 리스너가 적재 전에 UK 와 보조 인덱스를 만든다")
                .containsExactlyInAnyOrderElementsOf(SECONDARY_INDEXES);
        assertThat(rowCount()).as("이 시점에는 아직 한 행도 적재되지 않았다").isZero();
    }

    @Test
    @DisplayName("적재가 끝난 뒤에도 세 인덱스가 그대로 있다")
    void 적재_후_인덱스_상태() throws Exception {
        launch(parameters(COUNT));

        assertThat(secondaryIndexNames()).containsExactlyInAnyOrderElementsOf(SECONDARY_INDEXES);
    }

    @Test
    @DisplayName("인덱스가 이미 있어도 다시 실행할 수 있다")
    void 재실행_안전() throws Exception {
        launch(parameters(COUNT));
        jdbcTemplate.execute("TRUNCATE TABLE member_a");

        JobExecution second = launch(parameters(COUNT));

        assertThat(second.getStatus())
                .as("인덱스 DDL 은 IF NOT EXISTS 라 두 번째 실행에서 실패하지 않는다")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(rowCount()).isEqualTo(COUNT);
    }

    @Test
    @DisplayName("member_a 가 비어 있지 않으면 시작하지 않는다")
    void 이어서_적재_차단() throws Exception {
        launch(parameters(COUNT));

        JobExecution second = launch(parameters(COUNT));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getStepExecutions()).as("Step 에 진입하지 않는다").isEmpty();
        assertThat(second.getAllFailureExceptions())
                .anySatisfy(exception -> assertThat(exception).hasMessageContaining("member_a"));
        assertThat(rowCount()).as("두 번째 실행은 아무것도 쓰지 않는다").isEqualTo(COUNT);
    }

    @Test
    @DisplayName("적재된 행의 값이 생성기가 만든 값과 일치한다")
    void 적재된_값_대조() throws Exception {
        launch(parameters(COUNT));

        MemberSeedGenerator generator = new MemberSeedGenerator(
                MemberA::new, 0, false, MemberSeedGenerator.DEFAULT_SEED, MemberSeedGenerator.BASE_TIME);
        MemberBase expected = generator.generate(7L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM member_a WHERE email = ?", String.class, expected.getEmail()))
                .isEqualTo(expected.getName());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grade FROM member_a WHERE email = ?", String.class, expected.getEmail()))
                .isEqualTo(expected.getGrade().name());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT point FROM member_a WHERE email = ?", Long.class, expected.getEmail()))
                .isEqualTo(expected.getPoint());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_a WHERE updated_at IS NOT NULL OR idempotency_key IS NOT NULL",
                Long.class))
                .as("적재 직후에는 아직 아무 행도 처리되지 않았다").isZero();
    }

    @Test
    @DisplayName("member_a 에는 오염 행이 없고 이메일이 UK 를 만족한다")
    void 오염_없는_데이터() throws Exception {
        launch(parameters(COUNT));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member_a WHERE point < 0 OR email NOT LIKE '%@%'", Long.class))
                .as("1번 문제는 쓰기 비용만 본다. 검증 실패 요인이 섞이면 측정이 흐려진다").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT email) FROM member_a", Long.class))
                .as("email UK 가 걸린 채로 적재되므로 중복이 하나만 있어도 Job 이 깨진다")
                .isEqualTo(COUNT);
    }

    @Test
    @DisplayName("id 는 1부터 건수까지 구멍 없이 채워진다 - 행별 INSERT 의 흔적")
    void 식별자_연속성() throws Exception {
        launch(parameters(COUNT));

        assertThat(jdbcTemplate.queryForObject("SELECT MIN(id) FROM member_a", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT MAX(id) FROM member_a", Long.class))
                .as("IDENTITY 채번이 행마다 왕복하므로 AUTO_INCREMENT 블록 할당으로 인한 구멍이 없다")
                .isEqualTo(COUNT);
    }

    private JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(insertJob, parameters);
    }

    /**
     * Job 파라미터를 만든다. {@code run.id} 는 incrementer 를 쓰지 않고 직접 넣는다. 테스트를 반복
     * 실행해도 같은 {@code JobInstance} 로 취급되지 않아야 하기 때문이다.
     */
    private JobParameters parameters(long count) {
        return new JobParametersBuilder()
                .addString("count", String.valueOf(count))
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }

    private List<String> secondaryIndexNames() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT INDEX_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'member_a'
                  AND INDEX_NAME <> 'PRIMARY'""", String.class);
    }

    private Long rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_a", Long.class);
    }
}
