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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부록 B — 슬라이스 크기를 <b>0(분할 없음)</b> 으로 둔 극단값.
 *
 * <p>{@code update.slice-size=0} 이면 전 구간이 한 문장, 한 트랜잭션이 된다. 왕복은 이보다 적을 수
 * 없고(1회), 락 유지 시간은 이보다 나쁠 수 없다(전량이 커밋까지 잠긴다). <b>6번의 다이얼이 실재한다는
 * 것</b>을 이 시험이 고정한다 — 같은 코드가 설정 하나로 왕복 4회에서 1회로, 락 단위 5,000행에서
 * 20,000행으로 움직인다.
 *
 * <p>결과는 {@link AfterUpdateJobTest} 와 완전히 같아야 한다. 분할 방식은 <b>어떻게 잠그는가</b>만
 * 바꾸지 <b>무엇을 하는가</b>를 바꾸지 않는다. 그 사실이 서야 슬라이스 크기를 운영 상황에 맞춰
 * 고르는 판단이 가능해진다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        "update.slice-size=0"
})
@ActiveProfiles("after")
class AfterUpdateSliceSizeTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job updateJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GradeRecalcReporter reporter;

    @Autowired
    private SliceUpdateRecorder sliceRecorder;

    @Autowired
    private DatabaseWorkloadListener workloadListener;

    @BeforeEach
    void 데이터를_채운다() {
        UpdateFixture.seed(jdbcTemplate);
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("ALTER TABLE member_f DROP INDEX IF EXISTS idx_member_f_grade_point");
        jdbcTemplate.execute("TRUNCATE TABLE member_f");
    }

    @Test
    @DisplayName("분할하지 않으면 문장 하나가 전량을 갱신한다 - 왕복 최선, 락 최악")
    void 한_문장이_전량을_갱신한다() throws Exception {
        JobExecution execution = jobLauncher.run(updateJob,
                new JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        SliceUpdateReport report = sliceRecorder.report();

        assertThat(report.sliceCount())
                .as("슬라이스가 하나면 UPDATE 왕복도 한 번이다")
                .isEqualTo(1);
        assertThat(report.slices().get(0).slice().width())
                .as("한 문장이 키 공간 전체를 잠근다")
                .isEqualTo(UpdateFixture.COUNT);
        assertThat(report.totalUpdatedRows())
                .as("갱신 행 수는 슬라이스를 어떻게 자르든 같다")
                .isEqualTo(UpdateFixture.changedCount());
        assertThat(DatabaseWorkloadListener.updateRoundTrips(workloadListener.lastDelta()))
                .as("배치 메타데이터를 빼면 UPDATE 문은 단 하나다")
                .isLessThan(10);
        assertThat(reporter.current())
                .as("분할 방식은 어떻게 잠그는가만 바꾼다. 결과는 같아야 한다")
                .isEqualTo(UpdateFixture.expectedChecksum());
    }
}
