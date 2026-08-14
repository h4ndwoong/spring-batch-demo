package com.h4ndwoong.batchdemo.lookup;

/**
 * 추천인 조회 계측치. <b>4번 문제의 주 지표</b>인 "쿼리 / 행" 이 여기서 나온다.
 *
 * <p>{@code DatabaseWorkloadListener} 의 {@code COM_SELECT} 는 서버 전역 카운터라 배치 메타데이터
 * 조회까지 섞인다. 그것도 함께 보되, <b>조회 전략이 스스로 센 횟수</b>가 있어야 테스트가 정확한
 * 값({@code 2 × (N-1)} 또는 {@code 청크 수})으로 단언할 수 있다. 전역 카운터로는
 * "대략 이 정도" 밖에 말할 수 없다.
 *
 * @param lookups       {@code find} 가 실제로 조회를 요구받은 횟수. 추천인이 없는 행({@code id=1})은 세지 않는다
 * @param queries       그 답을 만들기 위해 서버로 보낸 SELECT <b>문</b>의 수. before 는 {@code 2 × lookups},
 *                      after 는 청크 수 수준이다
 * @param deduplicated  같은 청크 안에서 같은 추천인을 다시 요구받아 조회를 아낀 횟수.
 *                      before 는 청크를 모르므로 항상 0이다
 */
public record ReferrerLookupStats(long lookups, long queries, long deduplicated) {

    /** 아직 아무것도 조회하지 않은 상태. */
    public static final ReferrerLookupStats EMPTY = new ReferrerLookupStats(0, 0, 0);

    /**
     * 조회 1건을 만드는 데 든 왕복 횟수.
     *
     * <p>이 문제의 before/after 를 한 숫자로 요약하면 이것이다. before 는 {@code 2.0},
     * after 는 {@code 1 / 청크크기} 수준이다.
     *
     * @return 왕복 / 조회. 조회가 없었으면 0
     */
    public double queriesPerLookup() {
        return lookups == 0 ? 0 : (double) queries / lookups;
    }
}
