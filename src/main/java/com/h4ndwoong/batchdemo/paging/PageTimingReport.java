package com.h4ndwoong.batchdemo.paging;

import java.util.List;

/**
 * 한 Step 이 읽은 모든 페이지의 소요 시간과 그 요약. 3번 문제의 <b>결론이 담기는 값</b>이다.
 *
 * <p><b>왜 평균이 아니라 "구간 평균의 배율" 인가</b><br>
 * offset 페이징의 증상은 느린 것이 아니라 <b>점점</b> 느려지는 것이다. 전체 평균만 보면 before 도
 * after 도 그냥 "얼마" 라는 숫자 하나가 되어 성질의 차이가 사라진다. 앞 구간과 뒤 구간의 평균을
 * 따로 내고 그 배율을 보면
 * <ul>
 *   <li>배율 ≈ 1 — 페이지 위치와 무관하다 (키셋)</li>
 *   <li>배율 ≫ 1 — 뒤로 갈수록 버리는 비용이 커진다 (offset)</li>
 * </ul>
 * 가 한 숫자로 드러난다. 첫 페이지 한 장과 마지막 한 장만 비교하지 않는 이유는 버퍼 풀 상태나
 * 백그라운드 flush 로 개별 페이지가 튀기 때문이다.
 *
 * <p>이 타입은 값만 담는다. 표를 그리는 일은 {@link PageTimingRecorder} 의 몫이다.
 *
 * @param pages  페이지별 측정치. 페이지 번호 오름차순이며 비어 있을 수 있다
 * @param window 앞·뒤 구간에 각각 몇 페이지를 넣을지. 전체 페이지가 이보다 적으면 전체를 쓴다
 */
public record PageTimingReport(List<PageTiming> pages, int window) {

    /** 빈 보고. Step 이 한 페이지도 읽지 않았을 때. */
    public static final PageTimingReport EMPTY = new PageTimingReport(List.of(), 1);

    /**
     * 보고를 만든다. 페이지 목록은 방어적으로 복사한다.
     *
     * @param pages  페이지별 측정치
     * @param window 구간 크기. 1 이상이어야 한다
     */
    public PageTimingReport {
        if (window < 1) {
            throw new IllegalArgumentException("구간 크기는 1 이상이어야 한다: " + window);
        }
        pages = List.copyOf(pages);
    }

    /**
     * 읽은 페이지 수.
     *
     * <p>마지막에 <b>빈 페이지 한 장</b>이 포함되는 것이 정상이다. 전체 건수가 페이지 크기의 배수면
     * 리더는 "더 없음" 을 확인하기 위해 한 번 더 조회한다. 그 조회도 offset 이면 전체를 훑으므로
     * 비용이며, 그래서 측정에서 빼지 않는다.
     *
     * @return 페이지 수
     */
    public int pageCount() {
        return pages.size();
    }

    /**
     * 모든 페이지 획득에 걸린 시간의 합.
     *
     * @return 밀리초
     */
    public double totalMillis() {
        return pages.stream().mapToDouble(PageTiming::elapsedMillis).sum();
    }

    /**
     * 앞 구간의 페이지당 평균 소요 시간.
     *
     * @return 밀리초. 페이지가 없으면 {@code 0}
     */
    public double firstWindowAverageMillis() {
        return average(pages.subList(0, Math.min(window, pages.size())));
    }

    /**
     * 뒤 구간의 페이지당 평균 소요 시간.
     *
     * @return 밀리초. 페이지가 없으면 {@code 0}
     */
    public double lastWindowAverageMillis() {
        return average(pages.subList(Math.max(0, pages.size() - window), pages.size()));
    }

    /**
     * 뒤 구간이 앞 구간보다 몇 배 느린가. <b>3번 문제의 한 줄 요약</b>이다.
     *
     * @return 배율. 앞 구간이 0 이면(측정 해상도 아래) {@code 0}
     */
    public double growthRatio() {
        double first = firstWindowAverageMillis();
        if (first <= 0) {
            return 0;
        }
        return lastWindowAverageMillis() / first;
    }

    private static double average(List<PageTiming> window) {
        return window.stream().mapToDouble(PageTiming::elapsedMillis).average().orElse(0);
    }
}
