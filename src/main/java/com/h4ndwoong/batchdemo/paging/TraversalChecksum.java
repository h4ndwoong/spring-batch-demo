package com.h4ndwoong.batchdemo.paging;

/**
 * 순회한 행 집합의 지문. before 와 after 가 <b>같은 일을 했다</b>는 증거다.
 *
 * <p>3번 문제에서 가장 먼저 무너질 수 있는 것은 성능이 아니라 <b>정확성</b>이다. 페이징 방식을
 * 바꾸면 행을 건너뛰거나 중복해서 읽기 쉽고, 그렇게 되면 after 는 "빠른 구현" 이 아니라
 * "일을 덜 한 구현" 이다. 두 프로파일의 이 값이 문자 그대로 같아야 시간 비교가 의미를 갖는다.
 *
 * <p><b>왜 이 네 가지인가</b>
 * <ul>
 *   <li>{@code count} — 빠뜨렸는가</li>
 *   <li>{@code minId} / {@code maxId} — 범위의 양 끝에 닿았는가</li>
 *   <li>{@code idSum} — 중복해서 읽었는가. 건수가 같아도 같은 행을 두 번 읽고 다른 행을 빠뜨리면
 *       합이 달라진다. {@code id} 가 1..N 연속인 시드 데이터에서는
 *       {@code N(N+1)/2} 와 대조까지 가능하다</li>
 * </ul>
 * 200만 건의 합은 약 2×10<sup>12</sup> 로 {@code long} 안에 넉넉히 들어간다.
 *
 * @param count 순회한 행 수
 * @param minId 가장 작은 식별자. 한 행도 없으면 {@code null}
 * @param maxId 가장 큰 식별자. 한 행도 없으면 {@code null}
 * @param idSum 식별자의 합
 */
public record TraversalChecksum(long count, Long minId, Long maxId, long idSum) {

    /** 한 행도 순회하지 않은 상태. */
    public static final TraversalChecksum EMPTY = new TraversalChecksum(0, null, null, 0);

    /**
     * 행 하나를 반영한 새 체크섬을 만든다.
     *
     * <p>불변으로 두는 이유는 이 값이 <em>비교의 기준</em>이기 때문이다. 테스트가 중간 스냅샷을
     * 들고 있다가 나중에 단언하는 순간 값이 바뀌어 있으면 그 비교는 아무것도 증명하지 못한다.
     *
     * @param id 순회한 행의 식별자
     * @return 반영된 체크섬
     */
    public TraversalChecksum accumulate(long id) {
        return new TraversalChecksum(
                count + 1,
                minId == null ? id : Math.min(minId, id),
                maxId == null ? id : Math.max(maxId, id),
                idSum + id);
    }
}
