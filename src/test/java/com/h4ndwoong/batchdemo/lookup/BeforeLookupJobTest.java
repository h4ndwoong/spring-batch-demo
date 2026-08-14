package com.h4ndwoong.batchdemo.lookup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4번 문제 before 구성({@link BeforeLookupJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "after 와 같은 답을 낸다" 를 보이고, 여기 있는 시험이 <b>그런데 훨씬 많이
 * 왕복한다</b> 를 보인다. 이 문제에서 before 를 고발하는 증거는 시간이 아니라 <b>SELECT 문 수</b>다.
 * 시간은 실행 환경에 좌우되어 CI 에서 단언할 수 없지만, 왕복 횟수는 결정적이다.
 *
 * <p>2만 건의 이론값은 이렇다.
 * <pre>
 *   조회 요구 = 20,000 - 1 = 19,999        (id=1 은 추천인이 없다)
 *   SELECT   = 19,999 × 2 = 39,998        (추천인 조회 + 등급 확인)
 *   키셋이 아니라 청크로 묶으면 = 20        (청크 20개 × 1회)
 * </pre>
 * 50만 건에서는 이 차이가 100만 대 500, <b>2,000배</b>가 된다.
 */
@ActiveProfiles("before")
class BeforeLookupJobTest extends LookupJobContract {

    @Test
    @DisplayName("행마다 두 번 왕복한다 - N+1 의 정의 그 자체")
    void 행당_두_번_왕복() throws Exception {
        launch(parameters());

        ReferrerLookupStats stats = referrerLookup.stats();

        assertThat(stats.queries())
                .as("조회 요구 %d건 × 2회", stats.lookups()).isEqualTo(stats.lookups() * 2);
        assertThat(stats.queriesPerLookup()).isEqualTo(2.0);
        assertThat(stats.deduplicated())
                .as("청크를 모르므로 같은 추천인을 몇 번을 만나도 매번 조회한다").isZero();
    }

    @Test
    @DisplayName("서버가 센 SELECT 도 건수의 2배 수준이다 - 앱 카운터와 DB 카운터가 같은 이야기를 한다")
    void 서버_카운터도_같은_이야기를_한다() throws Exception {
        launch(parameters());

        assertThat(serverSelects())
                .as("배치 메타데이터 조회가 섞이므로 하한만 단언한다")
                .isGreaterThan(2 * (LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER));
    }

    @Test
    @DisplayName("청크 크기를 바꿔도 조회 횟수가 변하지 않는다 - 청크는 커밋 단위이지 조회 단위가 아니다")
    void 청크_크기는_before_를_구하지_못한다() throws Exception {
        launch(parameters());

        assertThat(referrerLookup.stats().queries())
                .as("청크 크기 %d 는 이 값에 아무 영향도 주지 않는다. after 에서만 조회 묶음이 된다",
                        chunkSize.value())
                .isEqualTo(2 * (LookupFixture.COUNT - LookupFixture.ROWS_WITHOUT_REFERRER));
    }
}
