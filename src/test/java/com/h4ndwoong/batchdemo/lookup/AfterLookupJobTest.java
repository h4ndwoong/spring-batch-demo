package com.h4ndwoong.batchdemo.lookup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4번 문제 after 구성({@link AfterLookupJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "before 와 같은 답을 낸다" 를 보이고, 여기 있는 시험이 <b>그 답을 청크당
 * 한 번의 왕복으로 냈다</b> 를 보인다.
 *
 * <p>청크 크기를 바꿨을 때 왕복이 반비례로 줄어드는 것은 {@link AfterLookupChunkSizeTest} 가 다른
 * 청크 크기로 확인한다 — 청크 크기가 Step 조립 시점에 확정되는 값이라 한 컨텍스트에서 두 값을
 * 시험할 수 없다 ({@link LookupChunkSize} 참고).
 */
@ActiveProfiles("after")
class AfterLookupJobTest extends LookupJobContract {

    @Test
    @DisplayName("청크당 한 번만 왕복한다 - 같은 조회 요구를 다른 횟수의 왕복으로 답한다")
    void 청크당_한_번_왕복() throws Exception {
        launch(parameters());

        long expectedChunks = LookupFixture.COUNT / chunkSize.value();

        assertThat(referrerLookup.stats().queries())
                .as("청크 %d개 × 1회. before 는 같은 데이터에 %d회였다",
                        expectedChunks, 2 * (LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER))
                .isEqualTo(expectedChunks);
        assertThat(referrerLookup.stats().queriesPerLookup())
                .as("before 는 2.0 이다").isLessThan(0.01);
    }

    @Test
    @DisplayName("조회 요구는 before 와 같은데 왕복만 줄었다 - 프로세서는 아무것도 아끼지 않았다")
    void 요구는_그대로다() throws Exception {
        launch(parameters());

        ReferrerLookupStats stats = referrerLookup.stats();

        assertThat(stats.lookups())
                .as("프로세서 코드는 before 와 문자 그대로 같다")
                .isEqualTo(LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER);
        assertThat(stats.queries()).isLessThan(stats.lookups());
    }

    @Test
    @DisplayName("서버가 센 SELECT 도 before 의 100분의 1 아래다")
    void 서버_카운터도_같은_이야기를_한다() throws Exception {
        launch(parameters());

        assertThat(serverSelects())
                .as("before 는 4만 회를 넘겼다. 여기 남는 것은 청크 %d회 + 배치 메타데이터뿐이다",
                        LookupFixture.COUNT / chunkSize.value())
                .isLessThan(2 * (LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER) / 100);
    }
}
