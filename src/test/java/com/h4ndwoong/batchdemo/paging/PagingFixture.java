package com.h4ndwoong.batchdemo.paging;

/**
 * 3번 문제의 before/after 통합 테스트가 <b>함께 쓰는</b> 상수.
 *
 * <p>두 테스트는 프로파일이 달라 한 컨텍스트에 담을 수 없다. 그래서 "before 와 after 의 순회
 * 결과가 같다" 를 한 테스트 안에서 직접 비교할 수 없고, 대신 <b>양쪽이 같은 상수와 대조</b>하게
 * 한다. 두 테스트가 같은 {@link #CHECKSUM} 을 통과하면 서로 같다는 뜻이다.
 *
 * <p>이 상수를 각 테스트에 복사해 두면 한쪽만 고쳤을 때 비교가 조용히 무너진다.
 */
final class PagingFixture {

    /**
     * 통합 테스트가 순회할 행 수.
     *
     * <p>페이지 크기의 배수로 잡았다. 마지막에 <b>빈 페이지 한 장</b>이 더 붙는 것이 실제 200만 건
     * 실행에서 일어나는 일이고({@link #PAGE_COUNT}), 그 페이지도 offset 이면 전체를 훑으므로
     * 측정에 포함되어야 한다.
     */
    static final long COUNT = 20_000L;

    /**
     * 기대 페이지 수. 20장 + <b>"더 없음" 을 확인하는 빈 페이지 1장</b>.
     *
     * <p>전체 건수가 페이지 크기의 배수일 때 리더가 한 번 더 조회하는 것은 페이징의 성질이지 결함이
     * 아니다. 200만 건 실행에서 2,001 페이지가 나오는 이유이기도 하다.
     */
    static final int PAGE_COUNT = (int) (COUNT / PagingJobCommonConfig.PAGE_SIZE) + 1;

    /**
     * 순회 결과의 지문. <b>before 와 after 가 이 값과 같아야 한다.</b>
     *
     * <p>{@code id} 가 1..N 연속이므로 합은 N(N+1)/2 다. 건수만 맞고 합이 다르면 어떤 행을 두 번
     * 읽고 다른 행을 빠뜨렸다는 뜻이다 — 페이징을 바꿀 때 가장 흔한 사고다.
     */
    static final TraversalChecksum CHECKSUM =
            new TraversalChecksum(COUNT, 1L, COUNT, COUNT * (COUNT + 1) / 2);

    /** {@code pages} 파라미터 검증에 쓸 페이지 수. */
    static final int PARTIAL_PAGES = 5;

    private PagingFixture() {
    }
}
