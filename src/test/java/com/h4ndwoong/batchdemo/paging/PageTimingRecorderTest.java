package com.h4ndwoong.batchdemo.paging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageTimingRecorder} 와 {@link PageTimingReport} 의 계산을 검증한다.
 *
 * <p>여기서 확인하는 것은 시간이 아니라 <b>해석</b>이다. 페이지별 소요 시간을 모아 "뒤 구간이 앞
 * 구간보다 몇 배 느린가" 로 바꾸는 계산이 틀리면, 3번 문제의 결론 자체가 틀린 숫자 위에 서게 된다.
 * 실제 소요 시간은 실행 환경에 좌우되므로 여기서는 <b>값을 직접 넣어</b> 계산만 본다.
 */
class PageTimingRecorderTest {

    private static final long MILLI = 1_000_000L;

    private final PageTimingRecorder recorder = new PageTimingRecorder();

    @Test
    @DisplayName("기록한 페이지가 순서대로 보고에 담긴다")
    void 기록_누적() {
        recorder.record(1, 1000, 5 * MILLI);
        recorder.record(2, 1000, 7 * MILLI);
        recorder.record(3, 500, 9 * MILLI);

        PageTimingReport report = recorder.report();

        assertThat(report.pageCount()).isEqualTo(3);
        assertThat(report.pages()).extracting(PageTiming::page).containsExactly(1, 2, 3);
        assertThat(report.pages()).extracting(PageTiming::rows).containsExactly(1000, 1000, 500);
        assertThat(report.totalMillis()).isEqualTo(21.0);
    }

    @Test
    @DisplayName("한 페이지도 읽지 않았으면 빈 보고다 - 0건 순회를 성공으로 읽지 않는다")
    void 빈_보고() {
        PageTimingReport report = recorder.report();

        assertThat(report).isEqualTo(PageTimingReport.EMPTY);
        assertThat(report.pageCount()).isZero();
        assertThat(report.growthRatio()).isZero();
        assertThat(report.totalMillis()).isZero();
    }

    @Test
    @DisplayName("뒤 구간이 앞 구간보다 몇 배 느린지 계산한다 - offset 페이징의 한 줄 요약")
    void 증가_배율() {
        for (int page = 1; page <= 20; page++) {
            recorder.record(page, 1000, page * MILLI);
        }

        PageTimingReport report = recorder.report();

        assertThat(report.firstWindowAverageMillis())
                .as("1~10 페이지 평균").isEqualTo(5.5);
        assertThat(report.lastWindowAverageMillis())
                .as("11~20 페이지 평균").isEqualTo(15.5);
        assertThat(report.growthRatio())
                .as("페이지 시간이 선형으로 늘면 배율이 1보다 뚜렷하게 크다")
                .isEqualTo(15.5 / 5.5);
    }

    @Test
    @DisplayName("페이지 시간이 평탄하면 배율이 1이다 - 키셋에서 기대하는 모양")
    void 평탄한_경우() {
        for (int page = 1; page <= 20; page++) {
            recorder.record(page, 1000, 4 * MILLI);
        }

        assertThat(recorder.report().growthRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("페이지가 구간 크기보다 적으면 있는 것만으로 계산한다")
    void 페이지가_적을_때() {
        recorder.record(1, 1000, 2 * MILLI);
        recorder.record(2, 1000, 6 * MILLI);

        PageTimingReport report = recorder.report();

        assertThat(report.firstWindowAverageMillis()).isEqualTo(4.0);
        assertThat(report.lastWindowAverageMillis()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Step 이 시작되면 이전 실행의 기록을 지운다 - 두 실행이 섞이면 앞 구간이 엉킨다")
    void 실행마다_초기화() {
        recorder.record(1, 1000, 5 * MILLI);

        recorder.beforeStep(stepExecution());
        recorder.record(1, 1000, 3 * MILLI);

        PageTimingReport report = recorder.report();

        assertThat(report.pageCount()).isEqualTo(1);
        assertThat(report.totalMillis()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("보고의 페이지 목록은 나중에 바뀌지 않는다 - 비교의 기준이므로")
    void 보고는_불변() {
        recorder.record(1, 1000, 5 * MILLI);
        PageTimingReport report = recorder.report();

        recorder.record(2, 1000, 5 * MILLI);

        assertThat(report.pageCount()).isEqualTo(1);
    }

    private static StepExecution stepExecution() {
        return new StepExecution("pagingStep", new JobExecution(1L));
    }
}
