package com.h4ndwoong.batchdemo.update;

import java.util.List;

/**
 * 한 Step 이 실행한 모든 슬라이스의 측정치와 그 요약. 6번 문제 after 의 <b>결론이 담기는 값</b>이다.
 *
 * <p><b>왜 합계가 아니라 최대값을 함께 보는가</b><br>
 * 총 시간만 보면 슬라이스를 어떻게 잘랐든 비슷하게 나온다 — 어차피 같은 행을 갱신하기 때문이다.
 * 달라지는 것은 <b>한 문장이 얼마나 오래 락을 붙잡았는가</b>이고, 그것은 평균이 아니라
 * {@link #maxElapsedMillis()} 가 말해준다. 슬라이스 크기를 4배로 키우면 총 시간은 거의 그대로인데
 * 이 값이 4배가 된다. <b>6번에서 after 가 지는 항목</b>이 이 숫자다.
 *
 * <p>이 타입은 값만 담는다. 표를 그리는 일은 {@link SliceUpdateRecorder} 의 몫이다 (3번의
 * {@code PageTimingReport} 와 같은 분리다).
 *
 * @param slices 슬라이스별 측정치. 순번 오름차순이며 비어 있을 수 있다
 */
public record SliceUpdateReport(List<SliceUpdate> slices) {

    /** 빈 보고. Step 이 한 슬라이스도 처리하지 않았을 때. */
    public static final SliceUpdateReport EMPTY = new SliceUpdateReport(List.of());

    /**
     * 보고를 만든다. 목록은 방어적으로 복사한다.
     *
     * @param slices 슬라이스별 측정치
     */
    public SliceUpdateReport {
        slices = List.copyOf(slices);
    }

    /**
     * 처리한 슬라이스 수. <b>이 값이 곧 UPDATE 왕복 횟수</b>다 (슬라이스당 문장 하나).
     *
     * @return 슬라이스 수
     */
    public int sliceCount() {
        return slices.size();
    }

    /**
     * 갱신된 행의 총합. <b>before 의 {@code WRITE_COUNT} 와 같아야 한다.</b>
     *
     * @return 행 수
     */
    public long totalUpdatedRows() {
        return slices.stream().mapToLong(SliceUpdate::updatedRows).sum();
    }

    /**
     * 모든 UPDATE 문에 걸린 시간의 합.
     *
     * @return 밀리초
     */
    public double totalMillis() {
        return slices.stream().mapToDouble(SliceUpdate::elapsedMillis).sum();
    }

    /**
     * 가장 오래 걸린 문장의 시간. <b>락 유지 시간의 상한</b>이다.
     *
     * @return 밀리초. 슬라이스가 없으면 {@code 0}
     */
    public double maxElapsedMillis() {
        return slices.stream().mapToDouble(SliceUpdate::elapsedMillis).max().orElse(0);
    }

    /**
     * 문장 하나가 평균 몇 밀리초 걸렸는가.
     *
     * @return 밀리초. 슬라이스가 없으면 {@code 0}
     */
    public double averageMillis() {
        return slices.stream().mapToDouble(SliceUpdate::elapsedMillis).average().orElse(0);
    }
}
