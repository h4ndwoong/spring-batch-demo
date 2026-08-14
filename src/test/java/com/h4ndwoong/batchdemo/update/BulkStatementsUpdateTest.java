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
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부록 A — <b>연결 설정으로는 6번을 살 수 없다.</b>
 *
 * <p>{@link BeforeUpdateJobTest} 와 문자 그대로 같은 구성을 돌린다. 다른 것은 JDBC URL 하나
 * ({@code useBulkStmts=true}) 뿐이다. 1번 문제에서 {@code rewriteBatchedStatements=true} 하나가
 * INSERT 왕복을 1,016분의 1로 만들었으니, UPDATE 에도 같은 종류의 해결이 있으리라 기대하게 되는
 * 자리다.
 *
 * <p><b>그 기대가 틀렸다는 것을 이 시험이 고정한다.</b> bulk 프로토콜은 배치를 패킷 하나에 묶어
 * 보내지만, 서버는 여전히 <b>행 수만큼의 문장을 실행</b>한다. 그래서 {@code COM_UPDATE} 는 before
 * 와 똑같이 갱신 행 수만큼 늘어난다 (2만 행 프로브 측정에서 문장 수 20,000 그대로, 대신
 * {@code Com_stmt_execute} 가 20,000 증가, 시간은 3.75s → 2.49s).
 *
 * <p>얻는 것이 없다는 뜻은 아니다 — 패킷이 줄어 시간은 짧아진다. 다만 <b>시간은 환경에 따라
 * 흔들리므로 시험이 단정하지 않는다.</b> 여기서 단정할 수 있는 것은 카운터이고, 그 카운터가 말하는
 * 바는 하나다. <b>문장 수를 줄이려면 문장을 바꿔야 한다</b> ({@link AfterUpdateJobConfig}).
 *
 * <p>결과 자체는 {@link BeforeUpdateJobTest} 와 완전히 같아야 한다. 연결 설정은
 * <b>어떻게 보내는가</b>만 바꾸지 <b>무엇을 하는가</b>를 바꾸지 않는다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL_WITH_BULK_STATEMENTS,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA
})
@ActiveProfiles("before")
class BulkStatementsUpdateTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job updateJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GradeRecalcReporter reporter;

    @Autowired
    private DatabaseWorkloadListener workloadListener;

    @BeforeEach
    void 데이터를_채운다() {
        UpdateFixture.seed(jdbcTemplate);
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_f");
    }

    @Test
    @DisplayName("bulk 로 묶어 보내도 서버가 실행하는 문장 수는 그대로다 - 1번의 해결책이 듣지 않는다")
    void 연결_설정은_문장_수를_줄이지_못한다() throws Exception {
        JobExecution execution = jobLauncher.run(updateJob,
                new JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Long> delta = workloadListener.lastDelta();
        long changed = UpdateFixture.changedCount();

        assertThat(DatabaseWorkloadListener.updateRoundTrips(delta))
                .as("드라이버가 묶은 것은 패킷이다. 서버는 여전히 갱신 행 %d 개만큼 문장을 실행한다", changed)
                .isGreaterThanOrEqualTo(changed);
        assertThat(delta.get(DatabaseWorkloadListener.ROWS_UPDATED))
                .as("갱신 행 수도 before 와 같다 - 연결 설정은 일의 양을 바꾸지 않는다")
                .isGreaterThanOrEqualTo(changed);
        assertThat(reporter.current())
                .as("연결 설정은 어떻게 보내는가만 바꾼다. 결과는 before 와 같아야 한다")
                .isEqualTo(UpdateFixture.expectedChecksum());
    }
}
