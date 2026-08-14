package com.h4ndwoong.batchdemo.restart;

/**
 * {@code member_e} 전체의 지문. <b>5번 문제에서 "같아야 하는 것" 은 이 값이다.</b>
 *
 * <p><b>비교 축이 4번과 90도 다르다.</b> 4번의 {@code GradeDecisionChecksum} 은 <em>before 와
 * after</em> 가 같은 답을 냈는지를 봤다. 여기서는 <b>같은 프로파일의 서로 다른 실행</b>이 같은 답을
 * 냈는지를 본다 — 그것이 멱등성의 정의이기 때문이다. after 는 2회차와 3회차의 지문이 완전히 같고,
 * before 는 3회차에서 달라진다.
 *
 * <p>네 숫자를 함께 두는 이유는 각각이 다른 사고를 잡기 때문이다.
 * <ul>
 *   <li>{@code pointSum} — 주 지표. 실행 횟수가 답을 바꿨는지</li>
 *   <li>{@code negativeRows} — <b>총합을 되돌려도 복구되지 않는 피해</b>. 이중 차감으로 포인트가
 *       음수가 된 행의 수다. 잘못된 배치의 피해는 집계값이 아니라 개별 행에 있다</li>
 *   <li>{@code processedRows} — 처리 흔적이 남은 행. before 는 언제나 0 이다</li>
 *   <li>{@code rowCount} — 대상 자체가 변하지 않았다는 확인. 이 배치는 행을 지우거나 만들지 않는다</li>
 * </ul>
 *
 * @param rowCount      {@code member_e} 의 전체 행 수
 * @param pointSum      포인트 총합
 * @param negativeRows  포인트가 음수인 행 수
 * @param processedRows {@code processed = 1} 인 행 수
 */
public record PointBalanceChecksum(long rowCount, long pointSum, long negativeRows, long processedRows) {

    /** 한 행도 없는 상태. */
    public static final PointBalanceChecksum EMPTY = new PointBalanceChecksum(0, 0, 0, 0);

    /**
     * 사람이 읽는 한 줄 요약.
     *
     * @return 요약 문자열
     */
    public String summary() {
        return "rowCount=%d, pointSum=%d, negativeRows=%d, processedRows=%d"
                .formatted(rowCount, pointSum, negativeRows, processedRows);
    }

    /**
     * 다른 지문과의 포인트 총합 차이.
     *
     * @param other 비교 대상. 보통 Step 시작 시점의 지문이다
     * @return {@code this.pointSum - other.pointSum}. 소멸이 일어났으면 음수다
     */
    public long pointDelta(PointBalanceChecksum other) {
        return pointSum - other.pointSum;
    }
}
