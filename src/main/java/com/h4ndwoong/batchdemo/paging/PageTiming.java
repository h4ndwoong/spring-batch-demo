package com.h4ndwoong.batchdemo.paging;

/**
 * 페이지 한 장을 가져오는 데 걸린 시간. 3번 문제의 <b>측정 단위</b>다.
 *
 * <p>여기서 재는 것은 <em>페이지 획득 SQL 1회</em>의 시간이지 청크 처리 시간이 아니다. 3번 문제의
 * 주장은 "뒤 페이지로 갈수록 <b>DB 가 앞 레코드를 버리는 비용</b>이 커진다" 이므로, 행 매핑이나
 * 커밋이 섞이면 그 주장이 흐려진다. {@link MeasuredPagingItemReader} 가 SQL 발행 구간만 감싸서
 * 이 값을 만든다.
 *
 * <p>나노초로 들고 있다가 밀리초로 보여 준다. 앞 페이지는 한 자리 밀리초라서 밀리초로 재면
 * 0 이 줄줄이 나오고, 그러면 "몇 배 느려졌는가" 를 계산할 수 없다.
 *
 * @param page         페이지 번호. <b>1부터</b> 센다 ({@code AbstractPagingItemReader.getPage()} 는 0부터다)
 * @param rows         그 페이지가 실제로 돌려준 행 수. 마지막 페이지는 페이지 크기보다 작거나 0이다
 * @param elapsedNanos 페이지 획득 SQL 에 걸린 시간(나노초)
 */
public record PageTiming(int page, int rows, long elapsedNanos) {

    private static final double NANOS_IN_MILLI = 1_000_000d;

    /**
     * 소요 시간을 밀리초로 환산한다.
     *
     * @return 밀리초. 소수점을 버리지 않는다
     */
    public double elapsedMillis() {
        return elapsedNanos / NANOS_IN_MILLI;
    }
}
