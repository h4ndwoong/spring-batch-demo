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
 * 1번 문제 after 구성({@link AfterInsertJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>검증하는 것은 "빨라졌다" 가 아니라 <b>"before 와 다른 경로로 갔다"</b> 이다. 속도는 100만 건
 * 실측의 몫이고, 1000건 테스트로는 다음 세 가지 구조적 차이만 확인한다.
 * <ul>
 *   <li>인덱스가 적재 <em>후</em>에 생긴다 (before 는 <em>전</em>)</li>
 *   <li>커밋이 청크 수만큼만 일어난다 (before 는 50배)</li>
 *   <li>INSERT 왕복이 행 수보다 훨씬 적다 (before 는 정확히 행 수)</li>
 * </ul>
 * 이 셋이 유지되는 한, 100만 건에서의 수치 차이는 이 차이의 결과다.
 *
 * <p>{@link TestDatabase#URL_WITH_BATCH_REWRITE} 를 쓴다. 인라인 테스트 프로퍼티가 프로파일 설정보다
 * 우선하므로, 실행 환경과 같은 JDBC 옵션을 여기서 다시 지정해야 왕복 횟수를 제대로 잰다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL_WITH_BATCH_REWRITE,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
@ActiveProfiles("after")
class AfterInsertJobTest {

    private static final long COUNT = 1_000L;

    private static final List<String> SECONDARY_INDEXES =
            List.of("uk_member_a_email", "idx_member_a_grade", "idx_member_a_created_at");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job insertJob;

    @Autowired
    private IndexPostCreationListener indexPostCreationListener;

    @Autowired
    private MemberAIndexCreator indexCreator;

    @Autowired
    private DatabaseWorkloadListener workloadListener;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 빈_테이블로_되돌린다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_a");
        indexCreator.drop();
    }

    @Test
    @DisplayName("지정한 건수를 member_a 에 적재하고 COMPLETED 로 끝난다")
    void 적재_성공() throws Exception {
        JobExecution execution = launch(parameters(COUNT));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rowCount()).isEqualTo(COUNT);

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        assertThat(stepExecution.getStepName())
                .as("before 와 같은 이름이어야 같은 축에서 비교할 수 있다").isEqualTo("insertStep");
        assertThat(stepExecution.getReadCount()).isEqualTo(COUNT);
        assertThat(stepExecution.getWriteCount()).isEqualTo(COUNT);
    }

    @Test
    @DisplayName("chunk(5000) 이므로 커밋이 before 의 50분의 1로 줄어든다")
    void 커밋_횟수() throws Exception {
        JobExecution execution = launch(parameters(COUNT));

        StepExecution stepExecution = execution.getStepExecutions().iterator().next();
        long beforeCommits = COUNT / BeforeInsertJobConfig.CHUNK_SIZE + 1;

        assertThat(stepExecution.getCommitCount())
                .as("1000건은 청크 하나에 다 들어가므로 1회 + 마지막 빈 읽기 1회")
                .isEqualTo(COUNT / AfterInsertJobConfig.CHUNK_SIZE + 1);
        assertThat((long) stepExecution.getCommitCount())
                .as("같은 데이터에 대해 before 는 %d회 커밋했다", beforeCommits)
                .isLessThan(beforeCommits);
    }

    @Test
    @DisplayName("INSERT 왕복이 행 수보다 훨씬 적다 - before 는 행당 1.0회")
    void 묶음_왕복() throws Exception {
        launch(parameters(COUNT));

        long roundTrips = DatabaseWorkloadListener.insertRoundTrips(workloadListener.lastDelta());

        assertThat((double) roundTrips / COUNT)
                .as("드라이버가 여러 행을 한 번에 보낸다. SQL 재작성이든 bulk 프로토콜이든 왕복은 줄어야 한다")
                .isLessThan(0.1);
        assertThat(roundTrips).as("아예 0이면 카운터를 잘못 읽고 있는 것이다").isPositive();
    }

    @Test
    @DisplayName("보조 인덱스는 적재가 끝난 뒤에 만들어진다 - before 와 정반대")
    void 인덱스_후생성() throws Exception {
        indexCreator.create();

        JobExecution execution = launch(parameters(COUNT));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(secondaryIndexNames())
                .as("시작 시 남아 있던 인덱스를 치우고, 적재 후 다시 만든다")
                .containsExactlyInAnyOrderElementsOf(SECONDARY_INDEXES);
    }

    @Test
    @DisplayName("Job 시작 시 보조 인덱스를 제거해 PK 만 남긴다")
    void 시작_시_인덱스_제거() {
        indexCreator.create();

        indexPostCreationListener.beforeJob(null);

        assertThat(secondaryIndexNames())
                .as("before 를 돌린 뒤라도 after 는 PK 만 있는 상태에서 시작해야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("적재가 실패하면 인덱스를 만들지 않는다")
    void 실패하면_인덱스를_만들지_않는다() {
        JobExecution failed = new JobExecution(1L);
        failed.setStatus(BatchStatus.FAILED);

        indexPostCreationListener.afterJob(failed);

        assertThat(secondaryIndexNames())
                .as("깨진 적재 위에 인덱스를 만들면 다음 실행의 시작 상태가 오염된다")
                .isEmpty();
    }

    @Test
    @DisplayName("member_a 가 비어 있지 않으면 시작하지 않는다")
    void 이어서_적재_차단() throws Exception {
        launch(parameters(COUNT));

        JobExecution second = launch(parameters(COUNT));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getStepExecutions()).as("Step 에 진입하지 않는다").isEmpty();
        assertThat(rowCount()).as("두 번째 실행은 아무것도 쓰지 않는다").isEqualTo(COUNT);
    }

    @Test
    @DisplayName("적재된 값이 before 와 동일하다 - 같은 리더, 같은 시드")
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
                "SELECT COUNT(DISTINCT email) FROM member_a", Long.class))
                .as("적재 후 만드는 UK 가 성립하려면 이메일이 유일해야 한다")
                .isEqualTo(COUNT);
    }

    private JobExecution launch(JobParameters parameters) throws Exception {
        return jobLauncher.run(insertJob, parameters);
    }

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
