package com.h4ndwoong.batchdemo.update;

/**
 * 슬라이스 하나를 집합 UPDATE 로 처리한 결과. 6번 문제 after 의 <b>측정 단위</b>다.
 *
 * <p>세 값이 각각 다른 질문에 답한다.
 * <ul>
 *   <li>{@code updatedRows} — <b>일의 양</b>. 모든 슬라이스의 합이 before 의 {@code WRITE_COUNT} 와
 *       같아야 한다. 다르면 after 는 개선이 아니라 일을 빠뜨린 것이다</li>
 *   <li>{@code elapsedNanos} — <b>락을 잡고 있던 시간의 상한</b>. 문장 하나가 트랜잭션 하나이므로
 *       이 시간이 곧 그 구간이 잠겨 있던 시간이다. 전역 카운터
 *       {@code Innodb_row_lock_time} 은 <em>기다린</em> 시간이라 경합이 없으면 0 이고, 그래서
 *       "잡고 있던 시간" 은 여기서만 알 수 있다</li>
 *   <li>{@code slice} — 어느 구간이었나. 뒤 구간이 느려지는지(3번 문제의 증상이 여기서도 나오는지)
 *       를 보려면 순번이 필요하다</li>
 * </ul>
 *
 * <p>나노초로 들고 있다가 밀리초로 보여 주는 이유는 3번의 {@code PageTiming} 과 같다.
 *
 * @param slice        처리한 구간
 * @param updatedRows  실제로 갱신된 행 수. 등급이 이미 옳던 행은 조건에 걸려 세지 않는다
 * @param elapsedNanos UPDATE 문 한 번에 걸린 시간(나노초)
 */
public record SliceUpdate(IdSlice slice, long updatedRows, long elapsedNanos) {

    private static final double NANOS_IN_MILLI = 1_000_000d;

    /**
     * 소요 시간을 밀리초로 환산한다.
     *
     * @return 밀리초
     */
    public double elapsedMillis() {
        return elapsedNanos / NANOS_IN_MILLI;
    }
}
