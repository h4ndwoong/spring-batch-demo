package com.h4ndwoong.batchdemo.lookup;

import com.h4ndwoong.batchdemo.support.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청크 크기를 키우면 왕복이 반비례로 줄어든다는 것을 확인한다. <b>4번 문제의 둘째 축</b>이다.
 *
 * <p>{@link AfterLookupJobTest} 와 같은 Job 을 <b>다른 청크 크기</b>로 돌린다. 별도의 클래스인
 * 이유는 청크 크기가 Step 을 조립하는 시점에 확정되는 값이라 한 컨텍스트 안에서 두 값을 시험할 수
 * 없기 때문이다 ({@link LookupChunkSize} 에 왜 Job 파라미터가 아닌지 적어 두었다).
 *
 * <p>여기서 확인하는 것은 "크면 좋다" 가 아니라 <b>"조회 묶음 크기가 곧 청크 크기다"</b> 라는
 * 사실이다. 대가(캐시가 들고 있는 추천인 수, {@code IN} 절의 바인딩 파라미터 수)는 같은 배율로
 * 커지며, 그 상한이 {@link LookupChunkSize#MAX} 다.
 */
@SpringBootTest(properties = {
        TestDatabase.URL,
        TestDatabase.BATCH_SCHEMA,
        TestDatabase.DOMAIN_SCHEMA,
        "lookup.chunk-size=5000"
})
@ActiveProfiles("after")
class AfterLookupChunkSizeTest {

    private static final int CHUNK_SIZE = 5_000;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job lookupJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferrerLookup referrerLookup;

    @Autowired
    private GradeDecisionItemWriter decisionWriter;

    @Autowired
    private LookupChunkSize chunkSize;

    @BeforeEach
    void 데이터를_채운다() {
        LookupFixture.seed(jdbcTemplate);
    }

    @AfterEach
    void 실습_테이블을_비운다() {
        jdbcTemplate.execute("TRUNCATE TABLE member_d");
    }

    @Test
    @DisplayName("청크를 5배로 키우면 왕복이 5분의 1이 된다 - 청크 크기가 곧 조회 묶음 크기다")
    void 청크가_커지면_왕복이_준다() throws Exception {
        jobLauncher.run(lookupJob, parameters());

        assertThat(chunkSize.value()).isEqualTo(CHUNK_SIZE);
        assertThat(referrerLookup.stats().queries())
                .as("기본 청크(%d)에서는 %d회였다",
                        LookupChunkSize.DEFAULT, LookupFixture.COUNT / LookupChunkSize.DEFAULT)
                .isEqualTo(LookupFixture.COUNT / CHUNK_SIZE);
    }

    @Test
    @DisplayName("청크 크기를 바꿔도 산정 결과는 같다 - 묶는 크기는 답을 바꾸지 않는다")
    void 결과는_그대로다() throws Exception {
        jobLauncher.run(lookupJob, parameters());

        assertThat(decisionWriter.checksum()).isEqualTo(LookupFixture.checksum(LookupFixture.COUNT));
    }

    @Test
    @DisplayName("상한을 넘는 청크 크기는 거부한다 - IN 절과 캐시에는 물리적 상한이 있다")
    void 상한을_넘으면_거부한다() {
        assertThat(LookupChunkSize.MAX).isGreaterThan(CHUNK_SIZE);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new LookupChunkSize(LookupChunkSize.MAX + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lookup.chunk-size");
    }

    private static JobParameters parameters() {
        return new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
    }
}
