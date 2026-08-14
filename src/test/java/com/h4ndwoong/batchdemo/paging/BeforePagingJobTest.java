package com.h4ndwoong.batchdemo.paging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3번 문제 before 구성({@link BeforePagingJobConfig})을 실제 MariaDB 에 실행해 검증한다.
 *
 * <p>상속받은 계약 시험이 "after 와 같은 일을 한다" 를 보이고, 여기 있는 시험이 <b>그런데 훨씬 많이
 * 읽는다</b> 를 보인다. 이 문제에서 before 를 고발하는 증거는 시간이 아니라 <b>스캔한 행 수</b>다.
 * 시간은 실행 환경에 좌우되어 CI 에서 단언할 수 없지만, 읽은 행 수는 결정적이다.
 *
 * <p>2만 건·1,000행 페이지의 이론값은 이렇다.
 * <pre>
 *   페이지 k 가 읽는 행 = k × 1,000            (앞의 (k-1) × 1,000 을 읽고 버린 뒤 1,000행)
 *   전체 = Σ(k × 1,000) + 마지막 빈 페이지 20,000 ≈ 230,000행 = 건수의 11.5배
 *   키셋이면 = 20,000행 = 건수의 1배
 * </pre>
 * 200만 건에서는 이 배율이 1,000배가 된다.
 */
@ActiveProfiles("before")
class BeforePagingJobTest extends PagingJobContract {

    @Test
    @DisplayName("건수보다 훨씬 많은 행을 읽는다 - offset 은 앞 레코드를 읽고 버린다")
    void 버리는_비용() throws Exception {
        launch(parameters());

        assertThat(rowsScanned())
                .as("이론값은 건수의 약 11.5배(230,000행)다. 여유를 두고 3배로 단언한다")
                .isGreaterThan(PagingFixture.COUNT * 3);
    }

    @Test
    @DisplayName("페이지별 소요 시간이 기록된다 - 그래프의 원자료")
    void 측정치가_남는다() throws Exception {
        launch(parameters());

        PageTimingReport report = pageTimingRecorder.report();

        assertThat(report.pages()).extracting(PageTiming::page)
                .as("1부터 %d까지 빠짐없이", PagingFixture.PAGE_COUNT)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, PagingFixture.PAGE_COUNT).boxed().toList());
        assertThat(report.totalMillis()).isPositive();
    }
}
